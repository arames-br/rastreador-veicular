package br.com.rastreadorveicular

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth

class MainActivity : Activity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var status: TextView
    private lateinit var email: EditText
    private lateinit var password: EditText
    private val ownerEmail = "aramesbrasil40@gmail.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 24)
            setBackgroundColor(0xFFF5F6F8.toInt())
        }
        val title = TextView(this).apply {
            text = "RASTREADOR ARAMES BRASIL"
            textSize = 22f
            setTextColor(0xFF9B1C1C.toInt())
            gravity = Gravity.CENTER
        }
        val intro = TextView(this).apply {
            text = "Entre com a conta autorizada para usar o rastreador. As coordenadas só são visíveis para ${ownerEmail}."
            textSize = 15f
            setPadding(0, 16, 0, 10)
        }
        email = EditText(this).apply {
            hint = "E-mail (${ownerEmail})"
            inputType = 33
            setSingleLine(true)
        }
        password = EditText(this).apply {
            hint = "Senha da conta do rastreador"
            inputType = 129
            setSingleLine(true)
        }
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        val login = Button(this).apply {
            text = "ENTRAR"
            setOnClickListener { signIn() }
        }
        val register = Button(this).apply {
            text = "CRIAR CONTA / ENVIAR VERIFICAÇÃO"
            setOnClickListener { register() }
        }
        val verify = Button(this).apply {
            text = "JÁ VERIFIQUEI O E-MAIL — ATUALIZAR"
            setOnClickListener { refreshVerification() }
        }
        val start = Button(this).apply {
            text = "INICIAR RASTREAMENTO"
            setOnClickListener { checkPermissionsAndStart() }
        }
        val stop = Button(this).apply {
            text = "PARAR RASTREAMENTO"
            setOnClickListener { stopTracker() }
        }
        val settings = Button(this).apply {
            text = "CONFIGURAÇÕES DE PERMISSÃO"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        }
        root.addView(title, matchWrap())
        root.addView(intro, matchWrap())
        root.addView(email, matchWrap())
        root.addView(password, matchWrap())
        root.addView(status, matchWrap())
        listOf(login, register, verify, start, stop, settings).forEach { root.addView(it, matchWrap()) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        val user = auth.currentUser
        if (user != null) {
            email.setText(user.email ?: "")
            status.text = if (user.isEmailVerified) "Conta conectada e verificada." else "Confirme o link enviado por e-mail e toque em ATUALIZAR."
        } else status.text = "Sem sessão. Para criar a conta, use somente o e-mail autorizado."
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun inputs(): Pair<String, String>? {
        val e = email.text.toString().trim().lowercase()
        val p = password.text.toString()
        if (e != ownerEmail) {
            status.text = "Este rastreador aceita somente a conta autorizada $ownerEmail."
            return null
        }
        if (p.length < 6) {
            status.text = "Digite a senha da conta (mínimo 6 caracteres)."
            return null
        }
        return e to p
    }

    private fun signIn() {
        val values = inputs() ?: return
        status.text = "Entrando…"
        auth.signInWithEmailAndPassword(values.first, values.second)
            .addOnSuccessListener {
                password.text.clear()
                status.text = if (it.user?.isEmailVerified == true) "Conta verificada. Pode iniciar o rastreamento." else "A conta ainda não foi verificada. Abra o link enviado ao e-mail."
            }
            .addOnFailureListener { status.text = "Falha no login: ${it.localizedMessage ?: "verifique a conexão e os dados"}" }
    }

    private fun register() {
        val values = inputs() ?: return
        status.text = "Criando a conta…"
        auth.createUserWithEmailAndPassword(values.first, values.second)
            .addOnSuccessListener { result ->
                result.user?.sendEmailVerification()?.addOnSuccessListener {
                    password.text.clear()
                    status.text = "Conta criada. Abra o link de verificação enviado para $ownerEmail; depois volte e toque em ATUALIZAR."
                }?.addOnFailureListener { status.text = "Conta criada, mas não foi possível enviar o e-mail: ${it.localizedMessage}" }
            }
            .addOnFailureListener { status.text = "Falha ao criar conta: ${it.localizedMessage ?: "verifique a conexão"}" }
    }

    private fun refreshVerification() {
        val user = auth.currentUser
        if (user == null) { status.text = "Entre primeiro com sua conta."; return }
        user.reload().addOnSuccessListener {
            val current = auth.currentUser
            status.text = if (current?.isEmailVerified == true && current.email.equals(ownerEmail, true)) {
                "E-mail verificado. Pode iniciar o rastreamento."
            } else "Ainda não consta a verificação. Confirme o e-mail, aguarde alguns instantes e tente novamente."
        }.addOnFailureListener { status.text = "Não foi possível atualizar o status: ${it.localizedMessage}" }
    }

    private fun checkPermissionsAndStart() {
        val user = auth.currentUser
        if (user == null || !user.email.equals(ownerEmail, true)) {
            status.text = "Entre com a conta autorizada antes de iniciar."
            return
        }
        if (!user.isEmailVerified) {
            status.text = "Verifique o e-mail antes de iniciar o rastreamento."
            return
        }
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 100) else startTracker()
    }

    private fun startTracker() {
        val intent = Intent(this, LocationService::class.java).setAction(LocationService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        status.text = "Rastreamento iniciado. Deixe a notificação ativa. Se a internet cair, a posição mais recente fica guardada no celular e será enviada quando houver conexão."
        Toast.makeText(this, "Rastreamento iniciado", Toast.LENGTH_LONG).show()
    }

    private fun stopTracker() {
        stopService(Intent(this, LocationService::class.java).setAction(LocationService.ACTION_STOP))
        status.text = "Rastreamento parado pelo usuário."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val notificationsOkay = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (fineGranted && notificationsOkay) startTracker() else status.text = "Localização precisa e notificações são necessárias. Habilite-as nas configurações do app."
        }
    }
}
