# The ankka CLI, for `brew install thinkmorestupidless/tap/ankka`.
#
# Generated. In the ankka repository the version and the checksum are placeholders; the release
# workflow's `cli` job attaches the zip that sbt-native-packager built to the GitHub release for the
# tag, writes the tag's version and the zip's SHA-256 in here, and pushes homebrew/ to
# thinkmorestupidless/homebrew-tap. Changes go to the ankka repository, not to the tap.
class Ankka < Formula
  desc "Command-line client for ankka, a serverless platform for agentic AI"
  homepage "https://docs.ankka.cloud/"
  url "https://github.com/thinkmorestupidless/ankka/releases/download/v0.0.0/ankka-cli-0.0.0.zip"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install Dir["*"]
    (bin/"ankka").write_env_script libexec/"bin/ankka", Language::Java.java_home_env("21")
  end

  def caveats
    <<~EOS
      `ankka init` creates a service from the template by running `sbt new`, so it needs sbt:
        brew install sbt
      Every other command works without it.
    EOS
  end

  test do
    assert_equal version.to_s, shell_output("#{bin}/ankka version").strip
  end
end
