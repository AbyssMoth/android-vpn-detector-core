# android-vpn-detector-core

Текущая версия: `1.0.1`.

Чистое Android-ядро для определения **VPN** и **наличия интернета**, без зависимостей от
игрового движка. Только Android SDK. Одно ядро переиспользуется разными движками через
тонкие обвязки: Godot (`godot-vpn-detector`, подключает это ядро как submodule) и, при
необходимости, Unity (C#-мост поверх того же AAR).

Студийная библиотека Abyss Moth. Лицензия MIT.

## Что внутри

`com.abyssmoth.vpndetector.core.VpnDetectorCore` - один класс:

```java
VpnDetectorCore core = new VpnDetectorCore(context); // application context

core.startMonitoring(new VpnDetectorCore.Listener() {
    @Override public void onStatusChanged(boolean isVpn, boolean hasInternet) {
        // прокинуть в движок (сигнал Godot / событие Unity)
    }
});
core.stopMonitoring();

boolean vpn = core.isVpnActive();              // живая проверка
boolean net = core.hasInternetConnection();    // живая проверка
boolean cachedVpn = core.getCachedVpnStatus(); // последнее известное
boolean cachedNet = core.getCachedInternetStatus();

boolean opened = core.openVpnSettings(activity); // открыть системные настройки VPN
```

## Как устроено определение

- **VPN**: обход всех сетей, VPN активен если хотя бы у одной есть `TRANSPORT_VPN`.
- **Интернет**: обход всех сетей, интернет есть если хотя бы у одной есть
  `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`. Обход всех сетей важен: под
  VPN сама VPN-сеть часто не `VALIDATED`, а валидной остаётся подложка (Wi-Fi/LTE).
- Изменения ловятся через `ConnectivityManager.NetworkCallback` (rising/falling edge),
  не поллингом.

Весь код в try/catch: на кастомных прошивках вернёт `false` и не уронит приложение.

## Разрешения

`AndroidManifest.xml` добавляет `ACCESS_NETWORK_STATE` и `INTERNET`. Чувствительных
разрешений не требуется. Отключить чужой VPN программно нельзя (ограничение Android) -
`openVpnSettings` лишь открывает игроку системный экран.

## Сборка

```bash
./gradlew :vpndetectorcore:assembleRelease
# AAR: vpndetectorcore/build/outputs/aar/vpndetectorcore-release.aar
```

Требования: Android SDK, JDK 17-22, AGP 8.13.0, Gradle wrapper 8.13,
compileSdk 36, minSdk 23. Сборка запускается через `./gradlew`.

## Использование в Godot

Не напрямую. Godot-обёртка `godot-vpn-detector` подключает это ядро как git submodule и
делегирует в него из `GodotPlugin`. Для Unity аналогично пишется тонкий C#-мост поверх
собранного AAR.
