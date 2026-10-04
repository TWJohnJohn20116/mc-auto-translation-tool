## 這個 PR 做了什麼

<!-- 一兩句話說明。若與平台目標、CI 或發布流程有關，請一併說明。 -->

## 檢查清單

- [ ] 沒有在本機執行 Gradle、`scripts/build_*.ps1` 或任何 `scripts/test_*.py`（見 `AGENTS.md`）
- [ ] 改到平台目標時，已同步 `settings.gradle`、對應 workflow 的 matrix、`gradle.properties` 與 `AGENTS.md`
- [ ] 改到 workflow 時，已新增的 job 有加進 `ci-status` 的 `needs`
- [ ] 需要的話已更新 `CHANGELOG.md`

## CI

- [ ] `CI 總結` 為綠燈（`main` 的分支保護只要求這一個檢查）
- [ ] 若某個目標預期不會被建置，已確認它在 `gh run view <run-id> --json jobs` 中是 `skipped` 而不是失敗

## 備註

<!-- 需要 reviewer 注意的地方、已知限制，或後續工作。 -->
