# Выпуск версии и подпись APK

## 1. Создать ключ подписи (один раз)

Ключ подтверждает, что обновления выпускаете вы. **Без того же ключа Android не установит
обновление поверх** — храните `.jks` и пароли в надёжном месте (не в git!).

Android Studio: **Build → Generate Signed App Bundle / APK → APK → Create new…**
или в терминале:

```bash
keytool -genkeypair -v -keystore lingua-release.jks -alias lingua -keyalg RSA -keysize 4096 -validity 10000
```

## 2. Подключить ключ к сборке

Создайте `keystore.properties` в корне проекта (файл в `.gitignore`):

```properties
storeFile=/полный/путь/к/lingua-release.jks
storePassword=пароль_хранилища
keyAlias=lingua
keyPassword=пароль_ключа
```

```bash
./gradlew assembleRelease
```

Результат: `app/build/outputs/apk/release/app-release.apk` (подписан, уменьшен R8, ~2,5 МБ).
Без `keystore.properties` получится `app-release-unsigned.apk` — его нельзя установить.

> Debug-APK и release-APK подписаны разными ключами: чтобы перейти с debug на release,
> сначала сделайте резервную копию (Настройки → Данные), удалите debug-версию, установите
> release и восстановите копию.

## 3. Номер версии

`app/build.gradle.kts`: `versionCode` (целое, всегда +1) и `versionName` («1.0.0»).
Запишите изменения в `CHANGELOG.md`.

## 4. GitHub Releases (необязательно)

Workflow `.github/workflows/release.yml` собирает APK при push тега `v*`:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Для подписанного APK добавьте в **Settings → Secrets and variables → Actions**:
`LINGUA_KEYSTORE_BASE64` (содержимое `.jks` в base64: `base64 -w0 lingua-release.jks`),
`LINGUA_KEYSTORE_PASSWORD`, `LINGUA_KEY_ALIAS`, `LINGUA_KEY_PASSWORD`.
Без секретов к релизу прикладывается debug-APK.
