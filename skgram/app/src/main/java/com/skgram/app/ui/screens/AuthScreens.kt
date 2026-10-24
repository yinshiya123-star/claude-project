package com.skgram.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skgram.app.R
import com.skgram.app.core.Prefs
import com.skgram.app.core.Tg
import com.skgram.app.core.unit
import com.skgram.app.ui.components.Halo
import com.skgram.app.ui.components.SkButton
import com.skgram.app.ui.components.StripedBackground
import com.skgram.app.ui.theme.LocalSkColors
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.EmailAddressAuthenticationCode
import kotlinx.coroutines.launch

@Composable
fun AuthFlow(state: AuthorizationState?) {
    when (state) {
        is AuthorizationStateWaitTdlibParameters -> if (Prefs.hasApiCredentials) Splash() else ApiSetupScreen()
        is AuthorizationStateWaitPhoneNumber -> PhoneScreen()
        is AuthorizationStateWaitCode -> CodeScreen(state)
        is AuthorizationStateWaitPassword -> PasswordScreen(state)
        is AuthorizationStateWaitEmailAddress -> EmailScreen()
        is AuthorizationStateWaitEmailCode -> EmailCodeScreen()
        is AuthorizationStateWaitRegistration -> AuthCard(stringResource(R.string.app_name), stringResource(R.string.registration_unsupported)) {}
        else -> Splash()
    }
}

@Composable
fun Splash() {
    StripedBackground {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Halo(size = 96.dp)
            Spacer(Modifier.height(12.dp))
            Brand()
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun Brand() {
    Text(
        "SKGram", fontSize = 40.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
        color = LocalSkColors.current.headerStart, letterSpacing = 2.sp,
    )
}

/** Shared layout: emblem, title, description, then the step's fields. */
@Composable
private fun AuthCard(title: String, description: String, error: String? = null, content: @Composable () -> Unit) {
    StripedBackground {
        Column(
            Modifier.align(Alignment.Center).systemBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(24.dp).widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Halo(size = 72.dp)
            Brand()
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Text(description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Runs one auth step with a busy flag and the server's error text. */
private class Step {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun rememberStep() = remember { Step() }

@Composable
private fun ApiSetupScreen() {
    val scope = rememberCoroutineScope()
    val step = rememberStep()
    var id by rememberSaveable { mutableStateOf("") }
    var hash by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    AuthCard(stringResource(R.string.setup_title), stringResource(R.string.setup_desc), step.error) {
        OutlinedTextField(id, { id = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.api_id)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hash, { hash = it.trim() }, label = { Text(stringResource(R.string.api_hash)) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        SkButton(stringResource(R.string.next), enabled = !step.busy && id.isNotEmpty() && hash.length >= 16) {
            step.busy = true; step.error = null
            Prefs.setApiCredentials(id.toInt(), hash)
            scope.launch {
                Tg.sendParameters().onFailure { step.error = it.message }
                step.busy = false
            }
        }
        TextButton(onClick = { uri.openUri("https://my.telegram.org/apps") }) {
            Text(stringResource(R.string.setup_open_site))
        }
    }
}

@Composable
private fun PhoneScreen() {
    val scope = rememberCoroutineScope()
    val step = rememberStep()
    var phone by rememberSaveable { mutableStateOf("+") }
    AuthCard(stringResource(R.string.phone_title), stringResource(R.string.phone_desc), step.error) {
        OutlinedTextField(phone, { v -> phone = "+" + v.filter(Char::isDigit) }, label = { Text(stringResource(R.string.phone_hint)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        SkButton(stringResource(R.string.next), enabled = !step.busy && phone.length > 5) {
            step.busy = true; step.error = null
            scope.launch {
                Tg.client.setAuthenticationPhoneNumber(phone, null).unit().onFailure { step.error = it.message }
                step.busy = false
            }
        }
        // TDLib only rejects a wrong API ID/hash here, and they can't be changed without starting over.
        if (!Prefs.apiCredentialsBuiltIn) TextButton(onClick = {
            Prefs.clearApiCredentials()
            scope.launch { Tg.client.destroy() } // ends in AuthorizationStateClosed, which restarts the app
        }) { Text(stringResource(R.string.reenter_api)) }
    }
}

@Composable
private fun CodeScreen(state: AuthorizationStateWaitCode) {
    val scope = rememberCoroutineScope()
    val step = rememberStep()
    var code by rememberSaveable { mutableStateOf("") }
    AuthCard(stringResource(R.string.code_title), stringResource(R.string.code_desc, state.codeInfo.phoneNumber), step.error) {
        OutlinedTextField(code, { code = it.filter(Char::isLetterOrDigit) }, label = { Text(stringResource(R.string.code_hint)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        SkButton(stringResource(R.string.next), enabled = !step.busy && code.length >= 4) {
            step.busy = true; step.error = null
            scope.launch {
                Tg.client.checkAuthenticationCode(code).unit().onFailure { step.error = it.message }
                step.busy = false
            }
        }
    }
}

@Composable
private fun PasswordScreen(state: AuthorizationStateWaitPassword) {
    val scope = rememberCoroutineScope()
    val step = rememberStep()
    var pass by remember { mutableStateOf("") }
    val desc = if (state.passwordHint.isNotEmpty()) stringResource(R.string.password_hint_fmt, state.passwordHint) else stringResource(R.string.password_desc)
    AuthCard(stringResource(R.string.password_title), desc, step.error) {
        OutlinedTextField(pass, { pass = it }, label = { Text(stringResource(R.string.password_hint)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        SkButton(stringResource(R.string.next), enabled = !step.busy && pass.isNotEmpty()) {
            step.busy = true; step.error = null
            scope.launch {
                Tg.client.checkAuthenticationPassword(pass).unit().onFailure { step.error = it.message }
                step.busy = false
            }
        }
    }
}

@Composable
private fun EmailScreen() {
    val scope = rememberCoroutineScope()
    val step = rememberStep()
    var email by rememberSaveable { mutableStateOf("") }
    AuthCard(stringResource(R.string.email_title), stringResource(R.string.email_desc), step.error) {
        OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        SkButton(stringResource(R.string.next), enabled = !step.busy && '@' in email) {
            step.busy = true; step.error = null
            scope.launch {
                Tg.client.setAuthenticationEmailAddress(email).unit().onFailure { step.error = it.message }
                step.busy = false
            }
        }
    }
}

@Composable
private fun EmailCodeScreen() {
    val scope = rememberCoroutineScope()
    val step = rememberStep()
    var code by rememberSaveable { mutableStateOf("") }
    AuthCard(stringResource(R.string.code_title), stringResource(R.string.email_code_desc), step.error) {
        OutlinedTextField(code, { code = it.trim() }, label = { Text(stringResource(R.string.code_hint)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        SkButton(stringResource(R.string.next), enabled = !step.busy && code.isNotEmpty()) {
            step.busy = true; step.error = null
            scope.launch {
                Tg.client.checkAuthenticationEmailCode(EmailAddressAuthenticationCode(code)).unit().onFailure { step.error = it.message }
                step.busy = false
            }
        }
    }
}
