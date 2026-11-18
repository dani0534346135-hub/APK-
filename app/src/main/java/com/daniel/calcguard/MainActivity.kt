package com.daniel.calcguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {

    private lateinit var display: TextView
    private var currentInput = ""
    private var firstOperand: Double? = null
    private var pendingOperator = ""
    private var justCalculated = false
    private val formatter = DecimalFormat("#.##########")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        display = findViewById(R.id.tvDisplay)

        val numberButtons = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        )

        for (id in numberButtons) {
            findViewById<Button>(id).setOnClickListener { btn ->
                val digit = (btn as Button).text.toString()
                if (justCalculated && pendingOperator.isEmpty()) {
                    currentInput = ""
                    firstOperand = null
                    justCalculated = false
                }
                if (currentInput == "0") currentInput = digit else currentInput += digit
                display.text = currentInput
            }
        }

        findViewById<Button>(R.id.btnDot).setOnClickListener {
            if (justCalculated && pendingOperator.isEmpty()) {
                currentInput = ""
                justCalculated = false
            }
            if (!currentInput.contains(".")) {
                currentInput = if (currentInput.isEmpty()) "0." else "$currentInput."
                display.text = currentInput
            }
        }

        findViewById<Button>(R.id.btnBackspace).setOnClickListener {
            if (currentInput.isNotEmpty()) {
                currentInput = currentInput.dropLast(1)
                display.text = currentInput.ifEmpty { "0" }
            }
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            currentInput = ""
            firstOperand = null
            pendingOperator = ""
            justCalculated = false
            display.text = "0"
        }

        // תמיכה בכפתור אחוזים (%)
        val grid = (display.parent as android.widget.LinearLayout).getChildAt(1) as android.widget.GridLayout
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (child is Button && child.text.toString() == "%") {
                child.setOnClickListener {
                    val value = currentInput.toDoubleOrNull() ?: return@setOnClickListener
                    val percentVal = if (firstOperand != null && pendingOperator.isNotEmpty()) {
                        firstOperand!! * (value / 100.0)
                    } else {
                        value / 100.0
                    }
                    currentInput = formatter.format(percentVal)
                    display.text = currentInput
                }
            }
        }

        val ops = mapOf(
            R.id.btnPlus to "+",
            R.id.btnMinus to "-",
            R.id.btnMul to "*",
            R.id.btnDiv to "/"
        )

        for ((id, op) in ops) {
            findViewById<Button>(id).setOnClickListener {
                val inputVal = currentInput.toDoubleOrNull()
                if (firstOperand != null && pendingOperator.isNotEmpty() && inputVal != null) {
                    val intermediate = calculate(firstOperand!!, inputVal, pendingOperator)
                    firstOperand = intermediate
                    display.text = if (intermediate.isNaN()) "שגיאה" else formatter.format(intermediate)
                } else if (inputVal != null) {
                    firstOperand = inputVal
                }
                pendingOperator = op
                currentInput = ""
                justCalculated = false
            }
        }

        findViewById<Button>(R.id.btnEquals).setOnClickListener {
            val prefs = getSharedPreferences("CalcGuardPrefs", Context.MODE_PRIVATE)
            val secretPin = prefs.getString("secret_pin", "123456")

            // פתיחת מסך השליטה הנסתר בהקשת הסיסמה + שווה
            if (currentInput == secretPin && pendingOperator.isEmpty()) {
                currentInput = ""
                firstOperand = null
                display.text = "0"
                startActivity(Intent(this, SettingsActivity::class.java))
                return@setOnClickListener
            }

            val a = firstOperand ?: return@setOnClickListener
            val b = currentInput.toDoubleOrNull() ?: return@setOnClickListener
            if (pendingOperator.isEmpty()) return@setOnClickListener

            val result = calculate(a, b, pendingOperator)
            if (result.isNaN()) {
                display.text = "לא ניתן לחלק ב-0"
                currentInput = ""
                firstOperand = null
            } else {
                val formatted = formatter.format(result)
                display.text = formatted
                currentInput = formatted
                firstOperand = result
            }
            pendingOperator = ""
            justCalculated = true
        }
    }

    private fun calculate(a: Double, b: Double, op: String): Double {
        return when (op) {
            "+" -> a + b
            "-" -> a - b
            "*" -> a * b
            "/" -> if (b != 0.0) a / b else Double.NaN
            else -> b
        }
    }
}
