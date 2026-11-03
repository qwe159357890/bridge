package my.bridge.com.automation.a11y

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import timber.log.Timber

/**
 * Bridge 极简输入法（IME，借鉴 Droidline 的 DroidKeyboard）。
 *
 * 用途：当 `ACTION_SET_TEXT` 失效（密码框、部分 App 自定义输入框、WebView 输入框）
 * 时作为文本输入的兜底。`ui_set_text` 命令会走三级降级：
 *   ACTION_SET_TEXT → 切换到本 IME → commitText。
 *
 * 本 IME 不显示任何软键盘 UI（无按键界面），只通过 InputConnection 提交文本与按键，
 * 因此对用户「无感」——切到它时屏幕上不会冒出键盘，输入完切回原输入法即可。
 *
 * 注意：要让 commitText 生效，用户需先在「系统设置 → 语言与输入法」中启用本 IME
 * （一次性操作）。ui_set_text 的 IME 兜底分支会在未启用时返回明确错误提示。
 */
class BridgeKeyboard : InputMethodService() {

    companion object {
        @Volatile
        private var instance: BridgeKeyboard? = null

        /** 是否已启用本 IME（读系统配置，供 UI/命令判断）。 */
        fun isEnabled(context: android.content.Context): Boolean {
            val id = "${context.packageName}/.automation.a11y.BridgeKeyboard"
            return try {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_INPUT_METHODS,
                )?.split(':')?.contains(id) == true
            } catch (e: Exception) {
                false
            }
        }

        /** 当前是否为默认输入法。 */
        fun isDefault(context: android.content.Context): Boolean {
            val id = "${context.packageName}/.automation.a11y.BridgeKeyboard"
            return try {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
                ) == id
            } catch (e: Exception) {
                false
            }
        }

        /**
         * 提交文本到当前输入目标（须 BridgeKeyboard 已是默认 IME 且有活动 InputConnection）。
         * 由 ui_set_text 的 IME 兜底分支调用。
         */
        fun commitText(text: String): Boolean {
            val svc = instance ?: return false
            val ic = svc.currentInputConnection ?: return false
            return try {
                ic.commitText(text, 1)
            } catch (e: Exception) {
                Timber.w(e, "BridgeKeyboard commitText 失败")
                false
            }
        }

        /**
         * 清空当前输入框并写入 text（selectAll + commit，用于 clear / replaceAll）。
         * 若 text 为空串则仅清空。
         */
        fun replaceAll(text: String): Boolean {
            val svc = instance ?: return false
            val ic = svc.currentInputConnection ?: return false
            return try {
                // 全选后提交（清空并替换）
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText(text, 1)
                true
            } catch (e: Exception) {
                Timber.w(e, "BridgeKeyboard replaceAll 失败")
                false
            }
        }

        /** 追加文本到输入框末尾（moveEnd + commit，用于 input append）。 */
        fun appendText(text: String): Boolean {
            val svc = instance ?: return false
            val ic = svc.currentInputConnection ?: return false
            return try {
                // 光标移到末尾再提交
                ic.performContextMenuAction(android.R.id.copy)
                ic.commitText(text, 1)
                true
            } catch (e: Exception) {
                Timber.w(e, "BridgeKeyboard appendText 失败")
                false
            }
        }

        /** 发送单个按键码（sendDownUpKeyEvents）。 */
        fun sendKey(keycode: Int): Boolean {
            val svc = instance ?: return false
            val ic = svc.currentInputConnection ?: return false
            return try {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keycode))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keycode))
                true
            } catch (e: Exception) {
                Timber.w(e, "BridgeKeyboard sendKey 失败")
                false
            }
        }

        /** 读剪贴板（Android 10+ 只有当前 IME 能读）。 */
        fun readClipboard(): String? {
            val svc = instance ?: return null
            return try {
                svc.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    ?.let { it as android.content.ClipboardManager }
                    ?.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(svc)
                    ?.toString()
            } catch (e: Exception) {
                Timber.w(e, "BridgeKeyboard readClipboard 失败")
                null
            }
        }
    }

    override fun onCreateInputView(): View? = null

    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) = Unit

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.d("BridgeKeyboard 输入法已创建")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
