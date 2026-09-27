"""Which sidecar the integration testkit starts when it is not told."""

import pytest

from ankka.testkit.integration import sidecar_image


def test_a_released_sdk_uses_the_sidecar_published_with_it(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANKKA_SIDECAR_IMAGE", raising=False)
    assert sidecar_image("0.7.0") == "ghcr.io/thinkmorestupidless/ankka-sidecar:0.7.0"


def test_an_unreleased_sdk_uses_the_sidecar_built_from_the_same_checkout(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("ANKKA_SIDECAR_IMAGE", raising=False)
    assert sidecar_image("0.0.0") == "ankka-sidecar:latest"
    assert sidecar_image() == "ankka-sidecar:latest"  # this checkout's own version is 0.0.0


def test_the_environment_wins(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ANKKA_SIDECAR_IMAGE", "registry.example/sidecar:dev")
    assert sidecar_image("0.7.0") == "registry.example/sidecar:dev"
