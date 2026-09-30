# Release process

Releases follow Semantic Versioning and are immutable after publication on Maven Central.

## Before tagging

1. Set the intended version in `VERSION`.
2. Add a matching `## <version>` section to `CHANGELOG.md`.
3. Review intentional public API changes and run `./gradlew updateKotlinAbi` when the ABI baseline must change.
4. Run:

   ```sh
   ./gradlew clean build checkKotlinAbi consumerTest validatePublication
   ```

5. Execute the release-candidate suite on the required physical device and emulator matrix described in [docs/device-validation.md](docs/device-validation.md). Attach the report to the release-validation issue.
6. Merge only after CI, consumer samples, publication validation and the hardware gate pass. A failed CI job may be waived for this release only when the owner explicitly approves a documented environment-only exception in the release-validation issue: identify the failing job and linked investigation, show that the equivalent device suite passes locally, confirm the remaining CI jobs pass, and explain why there is no confirmed library regression. Never waive a failed library test or an open P0/P1 library regression.

The repository must contain `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` and `SIGNING_PASSWORD` as GitHub Actions secrets.

## Publish

Create and push the immutable semantic tag that exactly matches `VERSION`:

```sh
git tag -s v0.2.0 -m "adb-utils 0.2.0"
git push origin v0.2.0
```

The Release workflow builds and signs the Maven bundle, uploads it with `publishingType=AUTOMATIC`, polls the Central Portal deployment until it reaches `PUBLISHED`, extracts notes from `CHANGELOG.md`, and creates the GitHub Release with the signed bundle attached.

Do not reuse a published version. If the workflow fails after Maven Central reports `PUBLISHED`, create the missing GitHub Release manually from the same tag and bundle instead of publishing the coordinates again.
