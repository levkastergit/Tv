# Выкладывает собранное приложение и список каналов в папку server, откуда их берёт кнопка «Обновить».
# Перед выпуском новой версии: увеличить versionCode в app/build.gradle и собрать (gradle assembleRelease).
$root = $PSScriptRoot
$build = "$root\app\build\outputs\apk\release"
$meta = (Get-Content "$build\output-metadata.json" -Raw | ConvertFrom-Json).elements[0]

New-Item -ItemType Directory -Force "$root\server" | Out-Null
Copy-Item "$build\app-release.apk" "$root\server\tv.apk"
Copy-Item "$root\app\src\main\assets\channels.json" "$root\server\channels.json"
@{ versionCode = $meta.versionCode; versionName = $meta.versionName; apk = "tv.apk" } |
    ConvertTo-Json | Out-File -Encoding ascii "$root\server\version.json"

"Выложена версия $($meta.versionName) (код $($meta.versionCode))"
