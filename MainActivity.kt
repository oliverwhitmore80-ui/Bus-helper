package com.example.bushelper

import android.app.*
import android.content.*
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.text.InputType
import android.view.*
import android.webkit.*
import android.widget.*
import java.util.*

class MainActivity : Activity() {
    private val url = "http://www.gspns.co.rs/red-voznje/prigradski"
    private lateinit var p: SharedPreferences
    private lateinit var wv: WebView
    private lateinit var sp: Spinner
    private lateinit var tvAlarm: TextView; private lateinit var tvNext: TextView
    private lateinit var tvInfo: TextView; private lateinit var tvList: TextView
    private lateinit var tvRules: TextView; private lateinit var tvStatus: TextView
    private lateinit var etLine: EditText; private lateinit var etWalk: EditText
    private lateinit var etArr: EditText; private lateinit var etTrav: EditText
    private lateinit var etMin: EditText; private lateinit var etMsg: EditText
    private var times = listOf<Int>()
    private var rules = mutableListOf<Pair<Int, String>>()   // minutes (negative = late), message
    private var locked: Int? = null
    private val dismissed = mutableSetOf<Int>()
    private var lastBeep = 0L
    private val h = Handler(Looper.getMainLooper())

    private fun f(m: Int) = "%02d:%02d".format((m / 60) % 24, m % 60)
    private fun et(hint: String, type: Int) = EditText(this).apply { this.hint = hint; inputType = type }
    private fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }
    private fun key() = "tt_" + etLine.text.toString().trim() + "_" + sp.selectedItemPosition

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        p = getSharedPreferences("bh", 0)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel("bus", "Bus", NotificationManager.IMPORTANCE_HIGH))
        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        tvAlarm = TextView(this).apply { setBackgroundColor(Color.RED); setTextColor(Color.WHITE); textSize = 20f; setPadding(24, 24, 24, 24); visibility = View.GONE }
        tvNext = TextView(this).apply { textSize = 44f }
        tvInfo = TextView(this).apply { textSize = 16f }
        etLine = et("Line (e.g. 5)", InputType.TYPE_CLASS_TEXT).apply { setText(p.getString("line", "5")) }
        sp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Weekday", "Saturday", "Sunday/holiday"))
            val d = Calendar.getInstance().get(Calendar.DAY_OF_WEEK); setSelection(if (d == 1) 2 else if (d == 7) 1 else 0)
        }
        etWalk = et("Minutes to walk to the stop", num).apply { setText(p.getString("walk", "5")) }
        etArr = et("Arrive by (HH:MM) - optional", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        etTrav = et("Trip takes (minutes)", num)
        etMin = et("Warn at minutes (negative = late, e.g. -5)", num)
        etMsg = et("Custom message", InputType.TYPE_CLASS_TEXT)
        tvRules = TextView(this); tvList = TextView(this); tvStatus = TextView(this)
        wv = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            addJavascriptInterface(object { @JavascriptInterface fun text(s: String) { runOnUiThread { parseTimes(s) } } }, "Android")
        }
        listOf(tvAlarm, btn("Made it ✔") { locked?.let { dismissed.add(it) }; locked = null; tvAlarm.visibility = View.GONE },
            tvNext, tvInfo,
            btn("Find bus for arrive-by") { locked = null; dismissed.clear(); savePrefs(); tick() }, etArr, etTrav, etWalk,
            etLine, sp, btn("Fetch timetable from website") { savePrefs(); refresh() }, tvStatus,
            etMin, etMsg,
            btn("Add warning") {
                val m = etMin.text.toString().toIntOrNull()
                if (m != null) { rules.add(m to etMsg.text.toString()); etMin.setText(""); etMsg.setText(""); savePrefs(); showRules() }
            },
            tvRules, btn("Clear warnings") { rules.clear(); savePrefs(); showRules() }, tvList
        ).forEach { root.addView(it) }
        root.addView(wv, LinearLayout.LayoutParams(2, 2))
        setContentView(ScrollView(this).apply { addView(root) })
        rules = (p.getString("rules", "") ?: "").split("\n").filter { it.contains("|") }
            .map { it.substringBefore("|").toInt() to it.substringAfter("|") }.toMutableList()
        times = (p.getString(key(), "") ?: "").split(",").mapNotNull { it.toIntOrNull() }
        showRules(); refresh()
        h.post(object : Runnable { override fun run() { tick(); h.postDelayed(this, 1000) } })
    }

    private fun savePrefs() = p.edit().putString("line", etLine.text.toString()).putString("walk", etWalk.text.toString())
        .putString("rules", rules.joinToString("\n") { "${it.first}|${it.second}" }).apply()

    private fun showRules() {
        tvRules.text = rules.joinToString("\n") { (m, s) -> (if (m >= 0) "$m min before" else "${-m} min late") + ": " + s }
    }

    private fun refresh() {
        val line = etLine.text.toString().trim(); val day = sp.selectedItemPosition
        tvStatus.text = "Loading timetable…"
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, u: String) { v.evaluateJavascript(js(line, day), null) }
        }
        wv.loadUrl(url)
    }

    // Drives the real page: picks day + line, presses ПРИКАЖИ, then returns the page text.
    private fun js(line: String, day: Int) = """
(function(){
 var L="$line", D=["Радни","Субота","Недеља"][$day];
 function txt(e){return (e.innerText||e.textContent||e.value||"").trim()}
 function fire(e){e.click();e.dispatchEvent(new Event("change",{bubbles:true}))}
 [].slice.call(document.querySelectorAll("input[type=radio],input[type=checkbox]")).forEach(function(i){
   var t=txt(i.closest("label")||i.parentElement||i); if(t.toLowerCase().indexOf(D.toLowerCase())==0)fire(i)});
 [].slice.call(document.querySelectorAll("label,li,option,span,a,td")).forEach(function(e){
   var t=txt(e); if(t==L||t.indexOf(L+" ")==0||t.indexOf(L+"-")==0){
     if(e.tagName=="OPTION"){[].slice.call(e.parentElement.options).forEach(function(o){o.selected=false});e.selected=true;fire(e.parentElement)}
     else{var i=e.querySelector("input")||(e.htmlFor&&document.getElementById(e.htmlFor)); if(i)fire(i); else e.click()}}});
 var b=[].slice.call(document.querySelectorAll("button,input[type=button],input[type=submit],a")).filter(function(x){return /прикажи|prikazi/i.test(txt(x))})[0];
 if(b)b.click();
 setTimeout(function(){Android.text(document.body.innerText)},4000);
})();"""

    private fun parseTimes(s: String) {
        var m = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""").findAll(s).map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }.toList().distinct().sorted()
        if (m.isEmpty()) m = Regex("""\b([01]?\d|2[0-3])\.([0-5]\d)\b(?!\.)""").findAll(s).map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }.toList().distinct().sorted()
        if (m.isEmpty()) { tvStatus.text = "No times found on the page (kept saved timetable)"; return }
        times = m; p.edit().putString(key(), m.joinToString(",")).apply()
        tvStatus.text = "Loaded ${m.size} departures"
    }

    private fun now(): Double { val c = Calendar.getInstance(); return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0 }

    private fun pick(n: Double): Int? {
        val arr = etArr.text.toString().split(":").takeIf { it.size == 2 }?.let { (it[0].toIntOrNull() ?: 0) * 60 + (it[1].toIntOrNull() ?: 0) }
        val trav = etTrav.text.toString().toIntOrNull() ?: 0
        val ok = times.filter { it !in dismissed }
        return if (arr != null) ok.filter { it <= arr - trav && it >= n }.lastOrNull() else ok.firstOrNull { it > n }
    }

    private fun tick() {
        val n = now()
        if (locked == null || n > locked!! + 60) locked = pick(n)
        val b = locked
        tvList.text = times.joinToString("  ") { f(it) }
        if (b == null) { tvNext.text = "--:--"; tvInfo.text = if (times.isEmpty()) "No timetable yet" else "No suitable bus"; tvAlarm.visibility = View.GONE; return }
        val left = Math.ceil(b - n).toInt(); val walk = etWalk.text.toString().toIntOrNull() ?: 0
        tvNext.text = f(b)
        tvInfo.text = (if (left >= 0) "Bus in $left min" else "Bus left ${-left} min ago") + "\nLeave home by ${f(b - walk)}" + (if (n >= b - walk && n < b) " (NOW)" else "")
        val active = rules.filter { n >= b - it.first && n < b - it.first + 90 }
        if (b !in dismissed && active.isNotEmpty()) {
            val late = n > b
            val msg = active.joinToString("\n") { it.second.ifBlank { "Bus alert" } } + "\n" + (if (late) "Bus left ${Math.floor(n - b).toInt()} min ago" else "Bus ${f(b)} in $left min")
            tvAlarm.text = msg; tvAlarm.visibility = View.VISIBLE
            if (System.currentTimeMillis() - lastBeep > 30000) {
                lastBeep = System.currentTimeMillis()
                try { ToneGenerator(AudioManager.STREAM_ALARM, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500) } catch (e: Exception) {}
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(longArrayOf(0, 300, 100, 300), -1)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1,
                    Notification.Builder(this, "bus").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Bus ${etLine.text}").setContentText(msg).build())
            }
        } else tvAlarm.visibility = View.GONE
    }
}
