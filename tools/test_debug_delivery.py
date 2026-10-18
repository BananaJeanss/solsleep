# SPDX-License-Identifier: GPL-3.0-or-later
"""Publication boundary/failure tests. APK parser/signature verification also runs on the real CI APK."""
import base64
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest
from debug_delivery import FILES, REPO, digest, validate_bundle
from prepare_ci_signing import prepare
from publish_debug_release import publish

SHA = "1" * 40


class FakeGitHub:
    def __init__(self, draft=None, assets=None):
        self.draft = draft
        self.assets = assets or {}
        self.calls = []
        self.commit = SHA
        self.damage_download = False
        self.api_failure = None

    def __call__(self, *args):
        self.calls.append(args)
        if args[:2] == ("gh", "api"):
            if "/git/ref/" in args[2]:
                return json.dumps(dict(object=dict(type="commit", sha=self.commit)))
            if self.api_failure:
                raise subprocess.CalledProcessError(1, args, self.api_failure)
            if self.draft is None:
                raise subprocess.CalledProcessError(1, args, "HTTP 404")
            return json.dumps(dict(draft=self.draft, prerelease=True, tag_name="debug-52.1-" + SHA[:12],
                                   assets=[dict(name=name) for name in self.assets]))
        action = args[2]
        if action == "create":
            assert "--draft" in args and "--prerelease" in args and "--latest=false" in args
            assert args[args.index("--target") + 1] == SHA
            self.draft = True
        elif action == "upload":
            assert "--clobber" not in args
            for name in args[6:]:
                p = Path(name)
                assert p.name not in self.assets
                self.assets[p.name] = p.read_bytes()
        elif action == "download":
            target = Path(args[args.index("--dir") + 1])
            for name, content in self.assets.items():
                target.joinpath(name).write_bytes(content)
            if self.damage_download:
                target.joinpath("solsleep-debug.apk").write_bytes(b"truncated")
        elif action == "edit":
            assert self.draft is True and set(self.assets) == FILES
            self.draft = False
        else:
            raise AssertionError(args)
        return ""


class DebugDeliveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name) / "bundle"
        self.directory.mkdir()
        self.directory.joinpath("solsleep-debug.apk").write_bytes(b"synthetic publication-boundary fixture; not an installable APK")
        self.info = dict(schema=1, repository=REPO, commit=SHA, tree="2" * 40, runId=1234, runNumber=52,
                         buildAttempt=1, applicationId="dev.bananajeans.solsleep.debug", versionCode=15201,
                         versionName="0.1.0-dev.52.1." + SHA[:12] + "-debug", minSdk=29, targetSdk=37,
                         debuggable=True, apkSha256=digest(self.directory / "solsleep-debug.apk"),
                         certificateSha256="a" * 64, signing="runner-ephemeral")
        self.directory.joinpath("APK_SIGNER.txt").write_text("Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n")
        self.write_info()

    def sums(self):
        self.directory.joinpath("SHA256SUMS").write_text("".join(
            f"{digest(self.directory / name)}  {name}\n" for name in sorted(FILES - {"SHA256SUMS"})))

    def write_info(self):
        self.directory.joinpath("build-info.json").write_text(json.dumps(self.info))
        self.sums()

    def validate(self, **overrides):
        expected = dict(commit=SHA, run_id=1234, run_number=52, attempt=1)
        expected.update(overrides)
        return validate_bundle(self.directory, **expected)

    def publish(self, gh):
        return publish(self.directory, commit=SHA, run_id=1234, run_number=52, attempt=1, run=gh)

    def assets(self):
        return {name: self.directory.joinpath(name).read_bytes() for name in FILES}

    def test_exact_bundle_and_counter_binding(self):
        self.assertEqual(self.validate(), self.info)
        for changed in (dict(commit="3" * 40), dict(run_id=1235), dict(run_number=51), dict(attempt=2)):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                self.validate(**changed)

    def test_apk_tamper_with_and_without_recomputed_file_checksums(self):
        self.directory.joinpath("solsleep-debug.apk").write_bytes(b"changed")
        with self.assertRaises(ValueError):
            self.validate()
        self.sums()
        with self.assertRaisesRegex(ValueError, "APK identity"):
            self.validate()

    def test_unknown_fields_bool_counters_production_identity_and_false_debuggable(self):
        original = self.info.copy()
        for changed in (dict(schema=2), dict(runId=True), dict(extra=1), dict(debuggable=False),
                        dict(applicationId="dev.bananajeans.solsleep"), dict(versionCode=1),
                        dict(signing="production"), dict(certificateSha256="Z" * 64)):
            self.info = original | changed
            self.write_info()
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                self.validate()

    def test_duplicate_json_and_changed_certificate_report_rejected(self):
        self.directory.joinpath("build-info.json").write_text(json.dumps(self.info)[:-1] + ', "schema": 1}')
        self.sums()
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.validate()
        self.write_info()
        self.directory.joinpath("APK_SIGNER.txt").write_text("Signer #1 certificate SHA-256 digest: " + "b" * 64 + "\n")
        self.sums()
        with self.assertRaisesRegex(ValueError, "Certificate"):
            self.validate()

    def test_extra_missing_and_symlink_files_rejected(self):
        extra = self.directory / "private.keystore"
        extra.write_bytes(b"must never ship")
        with self.assertRaises(ValueError):
            self.validate()
        extra.unlink()
        source = Path(self.tmp.name) / "actual.apk"
        shutil.move(self.directory / "solsleep-debug.apk", source)
        with self.assertRaises(ValueError):
            self.validate()
        self.directory.joinpath("solsleep-debug.apk").symlink_to(source)
        with self.assertRaises(ValueError):
            self.validate()

    def test_new_release_is_draft_until_exact_remote_readback(self):
        gh = FakeGitHub()
        self.assertTrue(self.publish(gh).endswith("debug-52.1-" + SHA[:12]))
        self.assertFalse(gh.draft)
        self.assertEqual(gh.assets, self.assets())
        actions = [c[2] for c in gh.calls if c[:2] == ("gh", "release")]
        self.assertEqual(actions, ["create", "upload", "download", "edit"])

    def test_partial_matching_draft_resumes_without_overwrite(self):
        first = {"solsleep-debug.apk": self.assets()["solsleep-debug.apk"]}
        gh = FakeGitHub(draft=True, assets=first)
        self.publish(gh)
        uploads = [c for c in gh.calls if c[:3] == ("gh", "release", "upload")]
        self.assertEqual({Path(p).name for p in uploads[0][6:]}, FILES - {"solsleep-debug.apk"})
        self.assertFalse(gh.draft)

    def test_changed_draft_asset_retained_without_upload_or_publication(self):
        gh = FakeGitHub(draft=True, assets={"solsleep-debug.apk": b"other build"})
        with self.assertRaises(ValueError):
            self.publish(gh)
        self.assertTrue(gh.draft)
        self.assertEqual(gh.assets["solsleep-debug.apk"], b"other build")
        self.assertFalse(any(c[:3] == ("gh", "release", "upload") for c in gh.calls))

    def test_failed_remote_readback_keeps_complete_draft_private(self):
        gh = FakeGitHub()
        gh.damage_download = True
        with self.assertRaises(ValueError):
            self.publish(gh)
        self.assertTrue(gh.draft)
        self.assertFalse(any(c[:3] == ("gh", "release", "edit") for c in gh.calls))

    def test_existing_identical_public_release_is_read_only(self):
        gh = FakeGitHub(draft=False, assets=self.assets())
        self.publish(gh)
        self.assertEqual([c[2] for c in gh.calls if c[:2] == ("gh", "release")], ["download"])

    def test_incomplete_public_or_extra_assets_never_mutated(self):
        for gh in (FakeGitHub(draft=False, assets={"solsleep-debug.apk": b"old"}),
                   FakeGitHub(draft=True, assets={"unknown.txt": b"retain"})):
            before = gh.assets.copy()
            with self.subTest(assets=before), self.assertRaises(ValueError):
                self.publish(gh)
            self.assertEqual(gh.assets, before)
            self.assertFalse(any(c[:2] == ("gh", "release") for c in gh.calls))

    def test_wrong_tag_commit_never_uploaded(self):
        gh = FakeGitHub(draft=True)
        gh.commit = "f" * 40
        with self.assertRaisesRegex(ValueError, "qualified commit"):
            self.publish(gh)
        self.assertFalse(gh.assets)

    def test_api_failure_is_not_misread_as_missing_release(self):
        gh = FakeGitHub()
        gh.api_failure = "HTTP 403 Forbidden"
        with self.assertRaises(subprocess.CalledProcessError):
            self.publish(gh)
        self.assertIsNone(gh.draft)
        self.assertFalse(any(c[:3] == ("gh", "release", "create") for c in gh.calls))

    def test_private_signing_material_exclusive_private_file_and_no_overwrite(self):
        data = b"synthetic test bytes; not a real key" * 16
        path = prepare(base64.b64encode(data).decode(), Path(self.tmp.name))
        self.assertEqual(path.read_bytes(), data)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        with self.assertRaises(FileExistsError):
            prepare(base64.b64encode(b"other" * 100).decode(), Path(self.tmp.name))
        self.assertEqual(path.read_bytes(), data)

    def test_bad_or_absent_signing_input_creates_no_file(self):
        target = Path(self.tmp.name) / "solsleep-ci-debug.keystore"
        self.assertIsNone(prepare("", Path(self.tmp.name)))
        for encoded in ("%%%", "a" * 131073, base64.b64encode(b"too small").decode()):
            with self.subTest(encoded_length=len(encoded)), self.assertRaises(ValueError):
                prepare(encoded, Path(self.tmp.name))
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
