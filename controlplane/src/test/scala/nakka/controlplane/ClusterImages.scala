package nakka.controlplane

import com.github.dockerjava.api.exception.NotFoundException
import org.testcontainers.DockerClientFactory
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.MountableFile

import java.nio.file.{Files, StandardCopyOption}

/**
 * Gets a locally built image into a throwaway k3s node.
 *
 * `kind load docker-image` has no equivalent for a testcontainers k3s container, and there is no
 * registry to pull from, so: save the image to a tar, copy it in, and import it into the node's
 * containerd. Every step was verified against `rancher/k3s:v1.31.2-k3s1` during planning
 * (specs/003-deploy-real-service/research.md, R1), because each has a way of failing that looks
 * like success.
 */
object ClusterImages:

  def importInto(k3s: K3sContainer, image: String): Unit =
    val docker = DockerClientFactory.instance().client()

    // `testOnly` bypasses the build wiring that produces this image (`Test / test` depends on it;
    // `testOnly` is a different key). Without this check a missing image presents as a pod stuck
    // in ErrImageNeverPull and a three-minute timeout with no hint as to why.
    try docker.inspectImageCmd(image).exec(): Unit
    catch
      case _: NotFoundException =>
        throw new IllegalStateException(
          s"image '$image' is not in the local Docker daemon. Build it first:\n" +
            "    sbt shoppingCart/Docker/publishLocal\n" +
            "(`sbt test` does this for you; `testOnly` does not.)"
        )

    val tar = Files.createTempFile("nakka-image", ".tar")
    try
      val (name, tag) = image.lastIndexOf(':') match
        case -1 => (image, "latest")
        case at => (image.substring(0, at), image.substring(at + 1))
      // No --platform equivalent, deliberately. It is needed only for multi-architecture *remote*
      // images; one built locally is single-architecture already, and pinning a platform here
      // would break whichever architecture was not the one pinned.
      val stream = docker.saveImageCmd(name).withTag(tag).exec()
      try Files.copy(stream, tar, StandardCopyOption.REPLACE_EXISTING): Unit
      finally stream.close()

      val inContainer = "/tmp/nakka-image.tar"
      k3s.copyFileToContainer(MountableFile.forHostPath(tar), inContainer)

      val result = k3s.execInContainer(
        // `ctr`, not `k3s ctr`: on this image the latter answers "No help topic for 'ctr'". It is
        // at /bin/ctr, a symlink to the k3s multicall binary, and works invoked directly.
        "ctr",
        // k3s runs its own containerd on a non-default socket; without this, ctr talks to one
        // that is not there.
        "-a",
        "/run/k3s/containerd/containerd.sock",
        // The one that fails silently. containerd namespaces are hard isolation: imported into
        // the default namespace the image imports fine, lists fine under `ctr images ls`, and
        // kubelet — which reads only k8s.io — still cannot see it.
        "-n",
        "k8s.io",
        "images",
        "import",
        inContainer
      )
      if result.getExitCode != 0 then
        throw new IllegalStateException(
          s"importing '$image' into k3s failed (${result.getExitCode}): ${result.getStderr}"
        )
    finally Files.deleteIfExists(tar): Unit
