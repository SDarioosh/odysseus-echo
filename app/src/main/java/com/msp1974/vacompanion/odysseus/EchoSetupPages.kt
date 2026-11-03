package com.msp1974.vacompanion.odysseus

// Odysseus fork: the phone pages of the Echo setup (EchoSetup.kt). Plain HTML, every value
// escaped; the only script is the nonce'd one on the Odysseus landing page.

import com.msp1974.vacompanion.odysseus.EchoSetup.Step
import com.msp1974.vacompanion.odysseus.EchoSetup.StepStatus
import com.msp1974.vacompanion.odysseus.EchoSetupRules.esc

object EchoSetupPages {

    fun page(title: String, body: String, refreshSeconds: Int? = null) = """<!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1"><meta name=referrer content=no-referrer>
        ${if (refreshSeconds != null) "<meta http-equiv=refresh content=$refreshSeconds>" else ""}<title>${esc(title)}</title>
        <style>body{font-family:system-ui,sans-serif;margin:24px;max-width:520px;color:#222}
        label{display:block;margin:16px 0;font-weight:600}input{display:block;width:100%;box-sizing:border-box;
        font-size:17px;padding:10px;margin-top:6px;border:1px solid #bbb;border-radius:8px}
        button,.btn{display:inline-block;font-size:18px;padding:12px 24px;border:0;border-radius:8px;background:#1565c0;color:#fff;text-decoration:none}
        .err{color:#b71c1c}.note{color:#555;font-size:15px}ul{padding-left:0;list-style:none}li{margin:10px 0}
        .ok{color:#2e7d32}.warn{color:#e65100}.run{color:#1565c0}.skip{color:#777}</style></head>
        <body><h2>${esc(title)}</h2>$body</body></html>"""

    private fun err(error: String?) = if (error != null) "<p class=err>${esc(error)}</p>" else ""

    fun startForm(
        code: String, error: String?, name: String, odysseus: String, needsName: Boolean, typedHost: String,
        tailscaleIp: String?, haLink: String?,
    ): String = page("Set up this Echo", """
        ${err(error)}
        ${if (haLink != null) "<p>Odysseus is linked. <a class=btn href=\"${esc(haLink)}\">Sign in to Home Assistant</a></p><p class=note>Or start again below.</p>" else ""}
        <form method=post action="/setup/${esc(code)}">
          <label>Name<input name=name required maxlength=${EchoSetupRules.MAX_NAME} value="${esc(name)}" placeholder="Kitchen" autocapitalize=words></label>
          <label>Odysseus address<input name=odysseus inputmode=url value="${esc(odysseus)}" placeholder="https://odysseus.example.ts.net"
            autocapitalize=off autocorrect=off></label>
          ${if (needsName) """<p class=note>This Echo has only its Tailscale address (${esc(tailscaleIp.orEmpty())}) here, and Home Assistant
            sign-in needs a name. Find it in the Tailscale admin console (Machines).</p>
          <label>This Echo's Tailscale name<input name=echo_host required value="${esc(typedHost)}" placeholder="echo-kitchen.example.ts.net"
            autocapitalize=off autocorrect=off></label>""" else ""}
          <button type=submit>Continue</button>
        </form>
        <p class=note>Next, Odysseus asks you to approve this Echo. If Odysseus asks you to sign in, sign in, then come back
        here and tap Continue again.</p>
        <p class=note>Leave the Odysseus address empty to set up only Home Assistant.</p>""")

    fun haUrlForm(code: String, error: String?, haUrl: String, odysseusStep: Step?): String = page("Home Assistant address", """
        ${if (odysseusStep != null) "<p>${stepLine(odysseusStep)}</p>" else ""}
        ${err(error)}
        <p>Odysseus didn't send a Home Assistant address. Enter the one this Echo should use.</p>
        <form method=post action="/setup/${esc(code)}/ha-url">
          <label>Home Assistant address<input name=ha_url inputmode=url required value="${esc(haUrl)}"
            placeholder="https://homeassistant.example.ts.net" autocapitalize=off autocorrect=off></label>
          <button type=submit>Sign in to Home Assistant</button>
        </form>
        <p class=note>Sign in with an administrator account: the Echo adds itself to Home Assistant with it and keeps the login for its dashboard.</p>""")

    /**
     * Odysseus sends the phone to /setup/<code>/odysseus#token=…&base_url=…&ha_url=… (a fragment
     * never reaches a server or a log). The script takes it out of the address bar and posts it here.
     */
    fun odysseusLanding(code: String, nonce: String): String = page("Linking to Odysseus", """
        <p id=w hidden>Saving the Odysseus link…</p>
        <p id=e hidden class=err>Nothing arrived from Odysseus. Go back to Odysseus and tap Approve again.</p>
        <noscript><p class=err>This page needs JavaScript to take over the link from Odysseus.</p></noscript>
        <form id=f method=post action="/setup/${esc(code)}/odysseus"></form>
        <script nonce="${esc(nonce)}">
          (function () {
            var h = location.hash.slice(1);
            history.replaceState(null, '', location.pathname);
            var f = document.getElementById('f'), n = 0;
            new URLSearchParams(h).forEach(function (v, k) {
              if (['token', 'base_url', 'ha_url'].indexOf(k) < 0) return;
              var i = document.createElement('input');
              i.type = 'hidden'; i.name = k; i.value = v; f.appendChild(i);
              if (k === 'token' && v) n++;
            });
            if (n) { document.getElementById('w').hidden = false; f.submit(); }
            else document.getElementById('e').hidden = false;
          })();
        </script>""")

    private fun stepLine(st: Step): String {
        val (cls, mark) = when (st.status) {
            StepStatus.OK -> "ok" to "✓"
            StepStatus.WARN -> "warn" to "!"
            StepStatus.FAILED -> "err" to "✗"
            StepStatus.RUNNING -> "run" to "…"
            StepStatus.SKIPPED -> "skip" to "–"
            StepStatus.PENDING -> "skip" to "·"
        }
        val detail = if (st.detail.isNotEmpty()) "<br><span class=note>${esc(st.detail)}</span>" else ""
        return "<span class=$cls>$mark <b>${esc(st.label)}</b></span>$detail"
    }

    fun progress(steps: List<Step>, running: Boolean): String {
        val failed = steps.any { it.status == StepStatus.FAILED }
        val end = when {
            running -> "<p class=note>This page updates by itself.</p>"
            failed -> "<p>Some steps failed; the others are kept. Fix the cause and run setup again from the Echo (Menu → Odysseus → Set up with phone): what's already done is found again, not added twice.</p>"
            else -> "<p class=ok><b>Done.</b> The Echo now shows your Home Assistant dashboard. You can close this page.</p>"
        }
        return page(if (running) "Setting up…" else if (failed) "Setup finished with problems" else "Echo set up",
            "<ul>${steps.joinToString("") { "<li>${stepLine(it)}</li>" }}</ul>$end",
            refreshSeconds = if (running) 2 else null)
    }
}
