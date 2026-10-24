package com.skgram.ui.screens

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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skgram.core.Accounts
import com.skgram.core.Prefs
import com.skgram.core.unit
import com.skgram.i18n.LocalStrings
import com.skgram.i18n.fmt
import com.skgram.ui.LocalAccount
import com.skgram.ui.components.Halo
import com.skgram.ui.components.SkButton
import com.skgram.ui.components.StripedBackground
import com.skgram.ui.theme.LocalSkColors
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
    val s = LocalStrings.current
    when (state) {
        is AuthorizationStateWaitTdlibParameters -> if (Prefs.hasApiCredentials) Splash() else ApiSetupScreen()
        is AuthorizationStateWaitPhoneNumber -> PhoneScreen()
        is AuthorizationStateWaitCode -> CodeScreen(state)
        is AuthorizationStateWaitPassword -> PasswordScreen(state)
        is AuthorizationStateWaitEmailAddress -> EmailScreen()
        is AuthorizationStateWaitEmailCode -> EmailCodeScreen()
        is AuthorizationStateWaitRegistration -> AuthCard("SKGram", s.registrationUnsupported) {}
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

/** Shared layout: emblem, title, description, then the step's fields; plus "cancel" when adding another account. */
@Composable
private fun AuthCard(title: String, description: String, error: String? = null, content: @Composable () -> Unit) {
    val account = LocalAccount.current
    val s = LocalStrings.current
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
            if (Accounts.othersReady(account).isNotEmpty()) {
                TextButton(onClick = { Accounts.cancel(account) }) { Text(s.cancel) }
            }
        }
    }
}

/** Busy flag and the server's error text for one auth step. */
private class Step {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun ApiSetupScreen() {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val step = remember { Step() }
    var id by rememberSaveable { mutableStateOf("") }
    var hash by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    AuthCard(s.setupTitle, s.setupDesc, step.error) {
        OutlinedTextField(id, { id = it.filter(Char::isDigit) }, label = { Text(s.apiId) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hash, { hash = it.trim() }, label = { Text(s.apiHash) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        SkButton(s.next, enabled = !step.busy && id.isNotEmpty() && hash.length >= 16) {
            step.busy = true; step.error = null
            Prefs.setApiCredentials(id.toInt(), hash)
            scope.launch {
                account.sendParameters().onFailure { step.error = it.message }
                step.busy = false
            }
        }
        TextButton(onClick = { uri.openUri("https://my.telegram.org/apps") }) { Text(s.setupOpenSite) }
        Text(s.setupHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PhoneScreen() {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val step = remember { Step() }
    var phone by rememberSaveable { mutableStateOf("+") }
    AuthCard(s.phoneTitle, s.phoneDesc, step.error) {
        OutlinedTextField(phone, { v -> phone = "+" + v.filter(Char::isDigit) }, label = { Text(s.phoneHint) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        SkButton(s.next, enabled = !step.busy && phone.length > 5) {
            step.busy = true; step.error = null
            scope.launch {
                account.client.setAuthenticationPhoneNumber(phone, null).unit().onFailure { step.error = it.message }
                step.busy = false
            }
        }
        // TDLib only rejects a wrong API ID/hash here, and they can't be changed without starting over.
        if (!Prefs.apiCredentialsBuiltIn && Accounts.othersReady(account).isEmpty()) TextButton(onClick = {
            Prefs.clearApiCredentials()
            Accounts.cancel(account) // the account closes and a fresh sign-in starts
        }) { Text(s.reenterApi) }
    }
}

@Composable
private fun CodeScreen(state: AuthorizationStateWaitCode) {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val step = remember { Step() }
    var code by rememberSaveable { mutableStateOf("") }
    AuthCard(s.codeTitle, s.codeDesc.fmt(state.codeInfo.phoneNumber), step.error) {
        OutlinedTextField(code, { code = it.filter(Char::isLetterOrDigit) }, label = { Text(s.codeHint) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        SkButton(s.next, enabled = !step.busy && code.length >= 4) {
            step.busy = true; step.error = null
            scope.launch {
                account.client.checkAuthenticationCode(code).unit().onFailure { step.error = it.message }
                step.busy = false
            }
        }
    }
}

@Composable
private fun PasswordScreen(state: AuthorizationStateWaitPassword) {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val step = remember { Step() }
    var pass by remember { mutableStateOf("") }
    val desc = if (state.passwordHint.isNotEmpty()) s.passwordHintFmt.fmt(state.passwordHint) else s.passwordDesc
    AuthCard(s.passwordTitle, desc, step.error) {
        OutlinedTextField(pass, { pass = it }, label = { Text(s.passwordHint) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        SkButton(s.next, enabled = !step.busy && pass.isNotEmpty()) {
            step.busy = true; step.error = null
            scope.launch {
                account.client.checkAuthenticationPassword(pass).unit().onFailure { step.error = it.message }
                step.busy = false
            }
        }
    }
}

@Composable
private fun EmailScreen() {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val step = remember { Step() }
    var email by rememberSaveable { mutableStateOf("") }
    AuthCard(s.emailTitle, s.emailDesc, step.error) {
        OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        SkButton(s.next, enabled = !step.busy && '@' in email) {
            step.busy = true; step.error = null
            scope.launch {
                account.client.setAuthenticationEmailAddress(email).unit().onFailure { step.error = it.message }
                step.busy = false
            }
        }
    }
}

@Composable
private fun EmailCodeScreen() {
    val account = LocalAccount.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val step = remember { Step() }
    var code by rememberSaveable { mutableStateOf("") }
    AuthCard(s.codeTitle, s.emailCodeDesc, step.error) {
        OutlinedTextField(code, { code = it.trim() }, label = { Text(s.codeHint) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        SkButton(s.next, enabled = !step.busy && code.isNotEmpty()) {
            step.busy = true; step.error = null
            scope.launch {
                account.client.checkAuthenticationEmailCode(EmailAddressAuthenticationCode(code)).unit().onFailure { step.error = it.message }
                step.busy = false
            }
        }
    }
}
