package com.example.serviceapp.ui.verifications.utils

import android.annotation.SuppressLint
import android.graphics.Color
import androidx.core.graphics.toColorInt
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LegendEntry
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.formatter.ValueFormatter

class ChartManager {

    fun setupLineChartAppearance(chart: LineChart, chartTitle: String, isAttackDetected: Boolean = false) {
        chart.setTouchEnabled(true)
        chart.isDragEnabled = true
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.setDrawGridBackground(false)
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.setNoDataText("No data available")

        setupXAxis(chart.xAxis)
        setupYAxis(chart.axisLeft, chart.axisRight)
        setupLegend(chart, chartTitle, isAttackDetected)

        chart.description.isEnabled = false
    }

    private fun setupXAxis(xAxis: XAxis) {
        xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(true)
            setDrawAxisLine(true)
            setAvoidFirstLastClipping(true)
            textColor = Color.WHITE
            textSize = 12f
            granularity = 1f
            isGranularityEnabled = true
            labelRotationAngle = 0f
            axisMinimum = 1f
            axisMaximum = 20f
            setLabelCount(20, true)

            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return if (value in 1f..20f) value.toInt().toString() else ""
                }
            }
        }
    }

    private fun setupYAxis(leftAxis: YAxis, rightAxis: YAxis) {
        leftAxis.apply {
            isEnabled = true
            setDrawAxisLine(true)
            setDrawGridLines(true)
            textColor = Color.WHITE
            textSize = 12f
            axisMinimum = 0f
            axisMaximum = 1.01f
            granularity = 0.1f
            isGranularityEnabled = true
            setLabelCount(6, true)
            valueFormatter = object : ValueFormatter() {
                @SuppressLint("DefaultLocale")
                override fun getFormattedValue(value: Float): String {
                    return String.format("%.1f", value)
                }
            }
        }

        rightAxis.apply {
            isEnabled = true
            axisMinimum = 0f
            axisMaximum = 1.01f
            setDrawAxisLine(false)
            setDrawGridLines(false)
        }
    }

    private fun setupLegend(chart: LineChart, chartTitle: String, isAttackDetected: Boolean) {
        val normalColor = getChartColor(chartTitle)
        val legendEntries = ArrayList<LegendEntry>()

        if (chartTitle == "Real-time Combined Confidence" && isAttackDetected) {
            val attackDetectedEntry = LegendEntry().apply {
                label = "$chartTitle (Attack Detected)"
                formColor = "#E53935".toColorInt()
                form = Legend.LegendForm.CIRCLE
            }
            legendEntries.add(attackDetectedEntry)
        } else {
            val customEntry = LegendEntry().apply {
                label = chartTitle
                formColor = normalColor
                form = Legend.LegendForm.CIRCLE
            }
            legendEntries.add(customEntry)
        }

        chart.legend.apply {
            isEnabled = true
            setCustom(legendEntries)
            textColor = if (isAttackDetected) "#E53935".toColorInt() else normalColor
            textSize = 12f
        }
    }

    fun plotLineChartData(chart: LineChart, dataSetName: String, data: List<Float>) {
        if (data.isEmpty()) {
            chart.data = null
            chart.invalidate()
            return
        }

        val totalCapacity = 20
        val visibleData = if (data.size > totalCapacity) data.takeLast(totalCapacity) else data

        val entries = visibleData.mapIndexed { index, value ->
            val xValue = (totalCapacity - visibleData.size + 1 + index).toFloat()
            Entry(xValue, value)
        }

        val dataSet = createLineDataSet(entries, dataSetName)
        val lineData = LineData(dataSet)
        chart.data = lineData

        chart.moveViewToX(20f)
        chart.notifyDataSetChanged()
        chart.invalidate()
    }

    private fun createLineDataSet(entries: List<Entry>, dataSetName: String): LineDataSet {
        return LineDataSet(entries, dataSetName).apply {
            val normalColor = getChartColor(dataSetName)
            val isCombined = dataSetName == "Combined"
            val threshold = 0.2f
            val lastScore = entries.lastOrNull()?.y ?: 1f

            color = if (isCombined && lastScore < threshold) {
                "#E53935".toColorInt()
            } else {
                normalColor
            }

            fillColor = color
            valueTextColor = Color.BLACK
            setDrawValues(false)
            setDrawCircles(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillAlpha = 70
        }
    }

    fun setupDoughnutChartAppearance(chart: PieChart, chartLabel: String) {
        chart.apply {
            setUsePercentValues(true)
            description.isEnabled = false
            setExtraOffsets(5f, 10f, 5f, 5f)
            setDragDecelerationFrictionCoef(0.95f)
            setDrawHoleEnabled(true)
            setHoleColor(Color.TRANSPARENT)
            setTransparentCircleColor(Color.TRANSPARENT)
            setTransparentCircleAlpha(110)
            setHoleRadius(65f)
            setTransparentCircleRadius(68f)
            setDrawCenterText(true)
            rotationAngle = 0f
            isRotationEnabled = false
            isHighlightPerTapEnabled = false
            legend.isEnabled = false
            setContentDescription(chartLabel)
        }
    }

    @SuppressLint("DefaultLocale")
    fun plotDoughnutChartData(chart: PieChart, confidenceScore: Float, chartName: String) {
        val entries = arrayListOf<PieEntry>().apply {
            add(PieEntry(confidenceScore, ""))
            add(PieEntry(1f - confidenceScore, ""))
        }

        val dataSet = PieDataSet(entries, chartName).apply {
            setDrawIcons(false)
            sliceSpace = 0f
            setDrawValues(false)

            val colors = arrayListOf<Int>().apply {
                add(getChartColor(chartName))
                add("#E0E0E0".toColorInt())
            }
            setColors(colors)
            selectionShift = 0f
        }

        val data = PieData(dataSet).apply {
            setValueFormatter(PercentFormatter(chart))
            setValueTextSize(0f)
            setValueTextColor(Color.BLACK)
        }

        chart.data = data

        val formattedScore = String.format("%.0f%%", confidenceScore * 100)
        chart.centerText = formattedScore
        chart.setCenterTextSize(20f)
        chart.setCenterTextColor("#333333".toColorInt())

        chart.invalidate()
    }

    private fun getChartColor(name: String): Int {
        return when (name) {
            "Face", "Real-time Face Confidence" -> "#1E88E5".toColorInt()
            "Audio", "Real-time Audio Confidence" -> "#43A047".toColorInt()
            "Gait", "Real-time Gait Confidence" -> "#8E24AA".toColorInt()
            "Combined", "Real-time Combined Confidence" -> "#FBC02D".toColorInt()
            else -> "#90A4AE".toColorInt()
        }
    }
}