package com.abyssmoth.vpndetector.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * VpnDetectorCore - определитель VPN и интернета для Android, БЕЗ зависимостей от
 * игрового движка. Только Android SDK. Обёртки под конкретные движки (Godot
 * GodotPlugin, Unity C#) делегируют сюда, поэтому одно и то же ядро переиспользуется.
 *
 * Принцип работы:
 *   - подписка на изменения сети через ConnectivityManager.NetworkCallback;
 *   - при каждом изменении состояние пересчитывается и, если изменилось,
 *     отдаётся наружу через Listener;
 *   - состояние читается обходом ВСЕХ сетей (а не только активной), потому что
 *     под VPN активная сеть-оверлей часто не имеет флага VALIDATED и проверка
 *     только активной сети ложно сообщала бы "интернета нет".
 *
 * Надёжность: весь код, дёргающий системные API, обёрнут в try/catch. На кастомных
 * или урезанных прошивках вызовы могут бросать исключения; ядро в этом случае
 * возвращает безопасные значения и НЕ роняет приложение.
 */
public class VpnDetectorCore {

    /** Колбэк изменения состояния сети. Реализуется обёрткой движка. */
    public interface Listener {
        void onStatusChanged(boolean isVpnActive, boolean hasInternet);
    }

    private static final String TAG = "VpnDetectorCore";

    private final Context appContext;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Listener listener;

    private volatile boolean isVpnActive = false;
    private volatile boolean hasInternet = false;
    private boolean isMonitoring = false;

    public VpnDetectorCore(Context context) {
        Context resolved = null;
        try {
            resolved = (context != null) ? context.getApplicationContext() : null;
        } catch (Throwable t) {
            Log.e(TAG, "context error: " + t.getMessage(), t);
        }
        this.appContext = resolved;
        try {
            if (appContext != null) {
                connectivityManager = (ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            }
        } catch (Throwable t) {
            Log.e(TAG, "init error: " + t.getMessage(), t);
        }
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /** Начать мониторинг. Повторные вызовы безопасны и игнорируются. */
    public void startMonitoring(Listener statusListener) {
        try {
            this.listener = statusListener;
            if (isMonitoring) {
                return;
            }
            if (connectivityManager == null) {
                Log.w(TAG, "ConnectivityManager is null, cannot start monitoring.");
                return;
            }
            registerCallback();
            isMonitoring = true;
            refreshState();
        } catch (Throwable t) {
            Log.e(TAG, "startMonitoring error: " + t.getMessage(), t);
        }
    }

    /** Остановить мониторинг и снять callback. Повторные вызовы безопасны. */
    public void stopMonitoring() {
        try {
            if (connectivityManager != null && networkCallback != null) {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Callback was not registered: " + e.getMessage());
                }
            }
            networkCallback = null;
            isMonitoring = false;
        } catch (Throwable t) {
            Log.e(TAG, "stopMonitoring error: " + t.getMessage(), t);
        }
    }

    /** Активен ли VPN прямо сейчас (живая проверка). */
    public boolean isVpnActive() {
        try {
            return checkVpn();
        } catch (Throwable t) {
            Log.e(TAG, "isVpnActive error: " + t.getMessage(), t);
            return false;
        }
    }

    /** Есть ли интернет прямо сейчас (живая проверка). */
    public boolean hasInternetConnection() {
        try {
            return checkInternet();
        } catch (Throwable t) {
            Log.e(TAG, "hasInternetConnection error: " + t.getMessage(), t);
            return false;
        }
    }

    /** Последний закэшированный статус VPN (без обращения к системе). */
    public boolean getCachedVpnStatus() {
        return isVpnActive;
    }

    /** Последний закэшированный статус интернета (без обращения к системе). */
    public boolean getCachedInternetStatus() {
        return hasInternet;
    }

    /**
     * Открыть системный экран настроек VPN. Отключить чужой VPN программно нельзя,
     * это даёт игроку быстрый путь сделать это вручную. Требует Activity (UI-поток).
     * Возвращает true, если экран удалось запустить.
     */
    public boolean openVpnSettings(final Activity activity) {
        try {
            if (activity == null) {
                return false;
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = new Intent(Settings.ACTION_VPN_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                    } catch (Throwable inner) {
                        Log.e(TAG, "openVpnSettings (ui) error: " + inner.getMessage(), inner);
                        openWirelessSettingsFallback(activity);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "openVpnSettings error: " + t.getMessage(), t);
            return false;
        }
    }

    // ─── Internal ───────────────────────────────────────────────────────────────

    private void openWirelessSettingsFallback(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "openWirelessSettingsFallback error: " + t.getMessage(), t);
        }
    }

    private void registerCallback() {
        final ConnectivityManager cm = connectivityManager;
        if (cm == null) {
            return;
        }

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                refreshState();
            }

            @Override
            public void onLost(Network network) {
                refreshState();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                refreshState();
            }
        };

        try {
            // registerDefaultNetworkCallback доступен с API 24; на 23 - обычный callback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(networkCallback);
            } else {
                cm.registerNetworkCallback(
                    new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    networkCallback
                );
            }
        } catch (Throwable t) {
            Log.e(TAG, "registerCallback error: " + t.getMessage(), t);
            networkCallback = null;
        }
    }

    private void refreshState() {
        try {
            boolean newVpn = checkVpn();
            boolean newInternet = checkInternet();

            boolean changed = (newVpn != isVpnActive) || (newInternet != hasInternet);

            isVpnActive = newVpn;
            hasInternet = newInternet;

            if (changed && listener != null) {
                try {
                    listener.onStatusChanged(newVpn, newInternet);
                } catch (Throwable t) {
                    Log.e(TAG, "listener error: " + t.getMessage(), t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "refreshState error: " + t.getMessage(), t);
        }
    }

    /** VPN считается активным, если ХОТЯ БЫ одна сеть имеет TRANSPORT_VPN. */
    private boolean checkVpn() {
        final ConnectivityManager cm = connectivityManager;
        if (cm == null) {
            return false;
        }
        try {
            Network[] networks = cm.getAllNetworks();
            if (networks == null) {
                return false;
            }
            for (Network network : networks) {
                try {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // Отдельная сеть могла исчезнуть между вызовами - пропускаем.
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "checkVpn error: " + t.getMessage(), t);
        }
        return false;
    }

    /**
     * Интернет есть, если ХОТЯ БЫ одна сеть имеет INTERNET + VALIDATED.
     * Обход всех сетей нужен потому, что под VPN валидной остаётся подложка
     * (Wi-Fi/cellular), а сама VPN-сеть VALIDATED может не иметь.
     */
    private boolean checkInternet() {
        final ConnectivityManager cm = connectivityManager;
        if (cm == null) {
            return false;
        }
        try {
            Network[] networks = cm.getAllNetworks();
            if (networks == null) {
                return false;
            }
            for (Network network : networks) {
                try {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // Игнорируем исчезнувшую сеть.
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "checkInternet error: " + t.getMessage(), t);
        }
        return false;
    }
}
