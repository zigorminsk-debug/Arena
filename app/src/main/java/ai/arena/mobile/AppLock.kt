package ai.arena.mobile

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

/**
 * Защита входа в приложение: биометрия или код блокировки устройства.
 *
 * В приложении лежат активные сессии сразу нескольких аккаунтов, поэтому вход
 * можно закрыть. Состояние «разблокировано» хранится в файле с отметкой времени,
 * чтобы его видели все процессы профилей (SharedPreferences между процессами
 * не синхронизируются). Пока приложением пользуются, отметка обновляется на
 * каждом возврате к экрану; если приложение пробыло в фоне дольше 5 минут,
 * подтверждение запрашивается снова.
 */
object AppLock {

    private const val STATE_FILE = "lock_state.json"
    private const val UNLOCKED_TTL_MS = 5 * 60 * 1000L

    fun isEnabled(ctx: Context): Boolean = SettingsStore.read(ctx).appLock

    fun isUnlocked(ctx: Context): Boolean {
        if (!isEnabled(ctx)) return true
        val state = readState(ctx) ?: return false
        val unlockedAt = state.optLong("unlockedAt", 0L)
        if (unlockedAt <= 0L) return false
        return System.currentTimeMillis() - unlockedAt <= UNLOCKED_TTL_MS
    }

    fun markUnlocked(ctx: Context) = writeState(ctx, System.currentTimeMillis())

    fun lockNow(ctx: Context) = writeState(ctx, 0L)

    private fun stateFile(ctx: Context) = File(ctx.filesDir, STATE_FILE)

    private fun readState(ctx: Context): JSONObject? = try {
        val file = stateFile(ctx)
        if (file.exists()) JSONObject(file.readText()) else null
    } catch (t: Throwable) {
        null
    }

    private fun writeState(ctx: Context, unlockedAt: Long) {
        try {
            val file = stateFile(ctx)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("unlockedAt", unlockedAt).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            // ignore
        }
    }
}

/**
 * Помощник для активити: проверяет блокировку и показывает запрос
 * биометрии/кода устройства. Создавать в onCreate.
 */
class AppLockGate(private val activity: AppCompatActivity) {

    private var pendingAction: (() -> Unit)? = null

    /** Запрос уже показывается — второй раз не спрашиваем. */
    private var prompting = false

    private val credentialLauncher = activity.registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            finishUnlock()
        } else {
            cancel(activity)
        }
    }

    /** Выполняет действие, если приложение разблокировано, иначе просит подтверждение. */
    fun ensure(action: () -> Unit) {
        if (!AppLock.isEnabled(activity)) {
            action()
            return
        }

        if (AppLock.isUnlocked(activity)) {
            // Пользователь работает с приложением — продлеваем окно доверия
            AppLock.markUnlocked(activity)
            action()
            return
        }

        if (prompting) {
            // Запрос уже на экране: выполним действие, когда подтвердят
            pendingAction = action
            return
        }

        prompting = true
        pendingAction = action
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val canUsePrompt = try {
            BiometricManager.from(activity).canAuthenticate(authenticators) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } catch (t: Throwable) {
            false
        }

        if (canUsePrompt) {
            showBiometricPrompt(authenticators)
        } else {
            showDeviceCredential()
        }
    }

    private fun showBiometricPrompt(authenticators: Int) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    finishUnlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        cancel(activity)
                    }
                    // Прочие ошибки (например, временная блокировка) оставляем
                    // пользователю: он может попробовать ещё раз, перезапустив экран
                }

                override fun onAuthenticationFailed() {
                    // Неудачная попытка — BiometricPrompt сам предложит повторить
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_title))
            .setSubtitle(activity.getString(R.string.lock_subtitle))
            .setAllowedAuthenticators(authenticators)
            .build()

        try {
            prompt.authenticate(info)
        } catch (t: Throwable) {
            showDeviceCredential()
        }
    }

    @Suppress("DEPRECATION")
    private fun showDeviceCredential() {
        val manager = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = if (manager.isDeviceSecure) {
            manager.createConfirmDeviceCredentialIntent(
                activity.getString(R.string.lock_title),
                activity.getString(R.string.lock_subtitle),
            )
        } else {
            null
        }

        if (intent != null) {
            try {
                credentialLauncher.launch(intent)
                return
            } catch (t: Throwable) {
                // ниже — разрешаем вход без блокировки
            }
        }

        // На устройстве нет ни биометрии, ни кода блокировки: защищать нечем
        Toast.makeText(activity, R.string.lock_not_available, Toast.LENGTH_LONG).show()
        finishUnlock()
    }

    private fun finishUnlock() {
        prompting = false
        AppLock.markUnlocked(activity)
        val action = pendingAction
        pendingAction = null
        action?.invoke()
    }

    private fun cancel(host: AppCompatActivity) {
        prompting = false
        pendingAction = null
        host.finish()
    }
}
