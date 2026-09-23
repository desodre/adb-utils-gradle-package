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

5. Merge only after CI, consumer samples and publication validation pass.

The repository must contain `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` and `SIGNING_PASSWORD` as GitHub Actions secrets.

## Publish

Create and push the immutable semantic tag that exactly matches `VERSION`:

```sh
git tag -s v0.2.0 -m "adb-utils 0.2.0"
git push origin v0.2.0
```

The Release workflow builds and signs the Maven bundle, uploads it with `publishingType=AUTOMATIC`, polls the Central Portal deployment until it reaches `PUBLISHED`, extracts notes from `CHANGELOG.md`, and creates the GitHub Release with the signed bundle attached.

Do not reuse a published version. If the workflow fails after Maven Central reports `PUBLISHED`, create the missing GitHub Release manually from the same tag and bundle instead of publishing the coordinates again.
