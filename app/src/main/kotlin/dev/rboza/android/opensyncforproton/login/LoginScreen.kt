package dev.rboza.android.opensyncforproton.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewFeature
import dev.rboza.android.opensyncforproton.R
import dev.rboza.android.opensyncforproton.account.LoginFailure
import dev.rboza.android.opensyncforproton.sync.lifecycle.RefusalReason
import dev.rboza.android.opensyncforproton.ui.BrandMark
import dev.rboza.android.opensyncforproton.ui.EyeCrossedIcon
import dev.rboza.android.opensyncforproton.ui.EyeIcon
import dev.rboza.android.opensyncforproton.ui.SectionCard
import kotlin.math.ceil

/**
 * The login steps. Fields carry autofill hints, so a password manager can fill them. Back, like Cancel, ends the
 * attempt (except while the login is saved); [onClosed] then leaves the screen.
 */
@Composable
fun LoginScreen(model: LoginModel, onClosed: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val shownStep by model.stepCount.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == LoginUi.Closed) onClosed() }
    BackHandler(enabled = state != LoginUi.Closed) { model.back() }

    Scaffold { padding ->
        val step = state
        if (step is LoginUi.Captcha && !step.loadFailed && CaptchaPolicy.supported(WebViewFeature::isFeatureSupported)) {
            CaptchaStep(step, model, Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BrandMark(size = 64.dp)
                Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.login_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            when (val step = state) {
                is LoginUi.Credentials -> {
                    Field(model.username, R.string.login_username, ContentType.Username, KeyboardType.Email)
                    SecretField(model.password, R.string.login_password, ContentType.Password, shownStep)
                    if (step.emptyInput) Problem(stringResource(R.string.login_empty_input))
                    Actions(model, R.string.login_submit, enabled = model.username.text.isNotBlank() && model.password.text.isNotEmpty())
                    Reassurance()
                }
                is LoginUi.Totp -> {
                    Text(stringResource(R.string.login_totp_prompt))
                    if (step.wrongCode) Problem(stringResource(R.string.login_totp_wrong))
                    // androidx.autofill's HintConstants.AUTOFILL_HINT_2FA_APP_OTP: a code from an authenticator app.
                    CodeField(model.code, R.string.login_totp_label, ContentType("2faAppOTPCode"))
                    if (step.emptyInput) Problem(stringResource(R.string.login_empty_input))
                    Actions(model, R.string.login_continue, enabled = model.code.text.isNotBlank())
                }
                is LoginUi.MailboxPassword -> {
                    Text(stringResource(R.string.login_mailbox_prompt))
                    if (step.wrongPassword) Problem(stringResource(R.string.login_mailbox_wrong))
                    SecretField(model.mailboxPassword, R.string.login_mailbox_label, ContentType.Password, shownStep)
                    if (step.emptyInput) Problem(stringResource(R.string.login_empty_input))
                    Actions(model, R.string.login_continue, enabled = model.mailboxPassword.text.isNotEmpty())
                    Reassurance()
                }
                is LoginUi.Captcha -> if (step.loadFailed) {
                    Problem(stringResource(R.string.login_captcha_load_failed))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = model::reloadCaptcha) { Text(stringResource(R.string.login_captcha_reload)) }
                        TextButton(onClick = model::cancel) { Text(stringResource(R.string.login_cancel)) }
                    }
                } else {
                    // Without the WebView features the page can't report back: fail closed.
                    Problem(stringResource(R.string.login_captcha_update_webview))
                    TextButton(onClick = model::cancel) { Text(stringResource(R.string.login_cancel)) }
                }
                LoginUi.Working -> {
                    Busy(R.string.login_working)
                    TextButton(onClick = model::cancel) { Text(stringResource(R.string.login_cancel)) }
                }
                LoginUi.Saving -> Busy(R.string.login_saving)
                is LoginUi.Done -> Ending(stringResource(if (step.resumed) R.string.login_done_resumed else R.string.login_done_new), model)
                is LoginUi.Refused -> Ending(stringResource(refusalText(step.reason)), model)
                is LoginUi.Failed -> {
                    Problem(failureText(step))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = model::retry) { Text(stringResource(R.string.login_try_again)) }
                        TextButton(onClick = model::cancel) { Text(stringResource(R.string.login_close)) }
                    }
                }
                LoginUi.Stopped -> {
                    Problem(stringResource(R.string.login_stopped))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = model::retry) { Text(stringResource(R.string.login_try_again)) }
                        TextButton(onClick = model::cancel) { Text(stringResource(R.string.login_close)) }
                    }
                }
                LoginUi.Closed -> Unit
            }
        }
    }
}

/**
 * Proton's captcha page, one WebView per [LoginUi.Captcha] state, torn down when the state goes (or the screen does).
 */
@Composable
private fun CaptchaStep(step: LoginUi.Captcha, model: LoginModel, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.login_notice), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.login_captcha_prompt))
        key(step) {
            AndroidView(
                factory = { context ->
                    CaptchaWebView(context, CaptchaPolicy(step.webUrl), onDone = model::captchaSolved, onLoadFailed = model::captchaLoadFailed)
                        .apply { load() }
                },
                onRelease = { it.tearDown() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        TextButton(onClick = model::cancel) { Text(stringResource(R.string.login_cancel)) }
    }
}

@Composable
private fun Field(state: TextFieldState, label: Int, hint: ContentType, keyboard: KeyboardType) {
    OutlinedTextField(
        state = state,
        label = { Text(stringResource(label)) },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().semantics { contentType = hint },
    )
}

/**
 * The six-digit code: one field, drawn as six boxes, the box for the next digit outlined in the primary colour. It
 * keeps only the ASCII digits of what is typed, pasted, or autofilled, the first six, so a pasted "123 456" fills all
 * six. TalkBack reads the one field, its label and its digits; the boxes are hidden from it.
 */
@Composable
private fun CodeField(state: TextFieldState, label: Int, hint: ContentType) {
    BasicTextField(
        state = state,
        inputTransformation = CodeDigits,
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier.fillMaxWidth().semantics { contentType = hint },
        decorator = { innerTextField ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    // The field itself, under the boxes: it takes the input and draws nothing.
                    Box(Modifier.matchParentSize().drawWithContent {}) { innerTextField() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(CODE_LENGTH) { i -> CodeBox(state.text.getOrNull(i), next = i == state.text.length, Modifier.weight(1f)) }
                    }
                }
            }
        },
    )
}

/** One box of the code; [next] (the box the next digit goes in) is also its `selected`, which only tests read. */
@Composable
private fun CodeBox(digit: Char?, next: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.height(56.dp)
            .border(if (next) 2.dp else 1.dp, if (next) colors.primary else colors.outline, MaterialTheme.shapes.small)
            .semantics(mergeDescendants = true) {
                hideFromAccessibility()
                selected = next
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(digit?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall)
    }
}

private const val CODE_LENGTH = 6

/**
 * Keeps the ASCII digits, the first six; the submit's own check stays (docs/protocol.md § Login, step 4). The cursor,
 * which isn't drawn, stays at the end, so a seventh digit is ignored and Backspace clears the last; a selection takes
 * the whole code, so a paste over it replaces the code.
 */
private object CodeDigits : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val digits = asCharSequence().filter { it in '0'..'9' }.take(CODE_LENGTH)
        if (digits.length != length) replace(0, length, digits)
        selection = if (selection.collapsed) TextRange(length) else TextRange(0, length)
    }
}

/**
 * A password field with an eye ("Show password"), which draws what was typed until the crossed-out eye ("Hide
 * password"). Shown is kept only while [step] (the model's count of steps) is, never in saved state, so each new step
 * starts hidden, an equal one too.
 */
@Composable
private fun SecretField(state: TextFieldState, label: Int, hint: ContentType, step: Int) {
    var shown by remember(step) { mutableStateOf(false) }
    OutlinedSecureTextField(
        state = state,
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        textObfuscationMode = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) EyeCrossedIcon else EyeIcon, contentDescription = stringResource(if (shown) R.string.login_hide else R.string.login_show))
            }
        },
        modifier = Modifier.fillMaxWidth().semantics { contentType = hint },
    )
}

@Composable
private fun Actions(model: LoginModel, submit: Int, enabled: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = model::submit, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(submit)) }
        TextButton(onClick = model::cancel) { Text(stringResource(R.string.login_cancel)) }
    }
}

/** Where the typed password goes. */
@Composable
private fun Reassurance() {
    SectionCard {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Lock, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.login_reassurance), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Busy(text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(stringResource(text))
    }
}

@Composable
private fun Ending(text: String, model: LoginModel) {
    Text(text)
    Button(onClick = model::cancel) { Text(stringResource(R.string.login_ok)) }
}

@Composable
private fun Problem(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error)
}

private fun refusalText(reason: RefusalReason) = when (reason) {
    RefusalReason.OTHER_ACCOUNT_ACTIVE -> R.string.login_refused_other_account
    RefusalReason.NAME_IN_USE -> R.string.login_refused_name_in_use
}

@Composable
private fun failureText(failed: LoginUi.Failed): String {
    val text = stringResource(
        when (failed.reason) {
            null -> R.string.login_failed_not_saved
            LoginFailure.EMPTY_INPUT -> R.string.login_empty_input
            LoginFailure.LOGIN_REFUSED -> R.string.login_failed_refused
            LoginFailure.SERVER_NOT_TRUSTED -> R.string.login_failed_server_not_trusted
            LoginFailure.FIDO_ONLY -> R.string.login_failed_fido_only
            LoginFailure.TWO_FA_UNSUPPORTED -> R.string.login_failed_two_fa_unsupported
            LoginFailure.PASSWORD_MODE_UNSUPPORTED -> R.string.login_failed_password_mode
            LoginFailure.VERIFY_IN_WEB_APP -> R.string.login_failed_verify_in_web_app
            LoginFailure.CAPTCHA_URL_REFUSED -> R.string.login_failed_captcha_url
            LoginFailure.CAPTCHA_TOKEN_REFUSED -> R.string.login_failed_captcha_token
            LoginFailure.KEY_V6, LoginFailure.KEY_POST_QUANTUM -> R.string.login_failed_key_newer
            LoginFailure.KEY_UNSUPPORTED -> R.string.login_failed_key_unsupported
            LoginFailure.KEY_CORRUPT -> R.string.login_failed_key_corrupt
            LoginFailure.RATE_LIMITED -> R.string.login_failed_rate_limited
            LoginFailure.NETWORK -> R.string.login_failed_network
            LoginFailure.APP_VERSION_REJECTED -> R.string.login_failed_app_version
            LoginFailure.SERVER_ERROR -> R.string.login_failed_server_error
        },
    )
    val wait = failed.retryAfter ?: return text
    val minutes = ceil(wait.inWholeSeconds / 60.0).toInt().coerceAtLeast(1)
    return text + " " + pluralStringResource(R.plurals.login_wait_minutes, minutes, minutes)
}
