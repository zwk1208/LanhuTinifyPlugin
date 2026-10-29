package com.lanhu.tinify

import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.JOptionPane
import javax.swing.SwingWorker
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.DefaultCellEditor
import kotlin.math.roundToInt

internal data class LanhuDesignSelection(
    val asset: LanhuDesignAsset,
    val density: Density,
    val variant: LanhuDesignVariant? = null,
    val share: LanhuShareInfo? = null
)

internal data class LanhuBatchAsset(
    val asset: LanhuDesignAsset,
    val share: LanhuShareInfo,
    val designLabel: String
)

internal object LanhuDesignSelectionDialog {
    fun showBatch(
        parent: java.awt.Component,
        assets: List<LanhuBatchAsset>,
        defaultDensity: Density,
        previewLoader: ((LanhuBatchAsset) -> ByteArray)? = null,
        variantPreviewLoader: ((LanhuBatchAsset, LanhuDesignVariant?) -> ByteArray)? = null,
        description: String
    ): List<LanhuDesignSelection>? {
        val displayAssets = assets.mapIndexed { index, item ->
            item.asset.copy(
                id = "__lanhu_batch_$index",
                name = "[${item.designLabel}] ${item.asset.name}"
            )
        }
        val byId = displayAssets.indices.associateBy { displayAssets[it].id }
        val selected = show(
            parent = parent,
            assets = displayAssets,
            defaultDensity = defaultDensity,
            previewLoader = previewLoader?.let { loader ->
                { asset -> loader(assets[byId.getValue(asset.id)]) }
            },
            variantPreviewLoader = variantPreviewLoader?.let { loader ->
                { asset, variant -> loader(assets[byId.getValue(asset.id)], variant) }
            },
            title = "选择要导入的蓝湖图片",
            description = description
        ) ?: return null
        return selected.map { selection ->
            val item = assets[byId.getValue(selection.asset.id)]
            selection.copy(asset = item.asset, share = item.share)
        }
    }

    fun show(
        parent: java.awt.Component,
        assets: List<LanhuDesignAsset>,
        defaultDensity: Density,
        previewLoader: ((LanhuDesignAsset) -> ByteArray)? = null,
        variantPreviewLoader: ((LanhuDesignAsset, LanhuDesignVariant?) -> ByteArray)? = null,
        title: String = "选择要导入的蓝湖图片和分辨率",
        description: String = "勾选要导入工程的图片，并为每张图片选择蓝湖分辨率和目标 Android density。"
    ): List<LanhuDesignSelection>? {
        if (assets.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "蓝湖项目中没有可导入的设计稿图片。", "蓝湖设计稿", JOptionPane.INFORMATION_MESSAGE)
            return null
        }
        val model = SelectionTableModel(assets, defaultDensity)
        val dialog = JDialog(
            javax.swing.SwingUtilities.getWindowAncestor(parent) as? Window,
            title,
            Dialog.ModalityType.APPLICATION_MODAL
        )
        val table = JTable(model).apply {
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            fillsViewportHeight = true
            rowHeight = 86
            intercellSpacing = Dimension(0, 1)
            gridColor = JBColor(java.awt.Color(225, 228, 232), java.awt.Color(70, 70, 70))
            selectionBackground = JBColor(java.awt.Color(218, 234, 252), java.awt.Color(55, 85, 120))
            selectionForeground = JBColor(java.awt.Color(30, 45, 60), java.awt.Color.WHITE)
            tableHeader.reorderingAllowed = false
            tableHeader.preferredSize = Dimension(0, 32)
            columnModel.getColumn(0).preferredWidth = 55
            columnModel.getColumn(1).preferredWidth = 90
            columnModel.getColumn(2).preferredWidth = 240
            columnModel.getColumn(3).preferredWidth = 110
            columnModel.getColumn(4).preferredWidth = 90
            columnModel.getColumn(5).preferredWidth = 140
            columnModel.getColumn(6).preferredWidth = 140
            columnModel.getColumn(0).cellEditor = DefaultCellEditor(JCheckBox())
            columnModel.getColumn(1).cellRenderer = PreviewCellRenderer()
            columnModel.getColumn(4).cellEditor = FormatCellEditor(model)
            columnModel.getColumn(5).cellEditor = VariantCellEditor(model)
            columnModel.getColumn(5).cellRenderer = VariantCellRenderer()
            columnModel.getColumn(6).cellEditor = DefaultCellEditor(
                JComboBox(Density.entries.map { it.resourceDirectory }.toTypedArray())
            )
            columnModel.getColumn(6).cellRenderer = DensityCellRenderer()
        }
        val selectedLabel = JLabel().apply { foreground = statusForeground() }
        val previewStatus = JLabel().apply { foreground = statusForeground() }
        val enlargeButton = JButton("放大预览").apply { isEnabled = false }
        fun updateCount() {
            selectedLabel.text = "已选择 ${model.selectedCount()} / ${assets.size} 张"
        }
        model.onChange = { updateCount() }
        model.onVariantChange = { row ->
            if (variantPreviewLoader != null) {
                previewStatus.text = "正在加载所选预览…"
                loadVariantPreview(model, assets[row], row, variantPreviewLoader, previewStatus)
            }
        }
        table.selectionModel.addListSelectionListener {
            enlargeButton.isEnabled = model.previewAt(table.selectedRow) != null
        }
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (event.clickCount == 2 && table.selectedRow >= 0) {
                    model.previewAt(table.selectedRow)?.let {
                        showLargePreview(dialog, assets[table.selectedRow].name, it)
                    }
                }
            }
        })
        enlargeButton.addActionListener {
            val row = table.selectedRow
            if (row >= 0) {
                model.previewAt(row)?.let { showLargePreview(dialog, assets[row].name, it) }
            }
        }

        var result: List<LanhuDesignSelection>? = null
        val confirmButton = JButton("导入选中图片").also {
            it.addActionListener {
                val selected = model.selections()
                if (selected.isEmpty()) {
                    JOptionPane.showMessageDialog(dialog, "请至少选择一张图片。", "蓝湖设计稿", JOptionPane.WARNING_MESSAGE)
                } else {
                    result = selected
                    dialog.dispose()
                }
            }
        }
        val cancelButton = JButton("取消").also { it.addActionListener { dialog.dispose() } }
        dialog.contentPane = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JLabel(description), BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(previewStatus)
                add(selectedLabel)
                add(enlargeButton)
                add(confirmButton)
                add(cancelButton)
            }, BorderLayout.SOUTH)
        }
        styleButtons(dialog.contentPane)
        updateCount()
        if (previewLoader != null) {
            previewStatus.text = "正在加载预览…"
            loadPreviews(model, assets, previewLoader, variantPreviewLoader, previewStatus)
        }
        dialog.setSize(980, 620)
        dialog.setLocationRelativeTo(parent)
        dialog.isVisible = true
        return result
    }

    private fun styleButtons(component: java.awt.Component) {
        if (component is JButton) {
            component.margin = java.awt.Insets(5, 11, 5, 11)
            component.isFocusPainted = false
        }
        (component as? java.awt.Container)?.components?.forEach(::styleButtons)
    }

    private fun statusForeground(): java.awt.Color =
        JBColor(java.awt.Color(63, 105, 145), java.awt.Color(135, 180, 220))

    private fun showLargePreview(parent: Window, name: String, image: BufferedImage) {
        val dialog = JDialog(parent, "预览：$name", Dialog.ModalityType.MODELESS)
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        dialog.contentPane = JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JScrollPane(JLabel(ImageIcon(image))), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(JButton("关闭").also { it.addActionListener { dialog.dispose() } })
            }, BorderLayout.SOUTH)
        }
        dialog.setSize(
            minOf(1200, bounds.width - 80),
            minOf(900, bounds.height - 80)
        )
        dialog.setLocationRelativeTo(parent)
        dialog.isVisible = true
    }

    private fun loadPreviews(
        model: SelectionTableModel,
        assets: List<LanhuDesignAsset>,
        previewLoader: (LanhuDesignAsset) -> ByteArray,
        variantPreviewLoader: ((LanhuDesignAsset, LanhuDesignVariant?) -> ByteArray)?,
        status: JLabel
    ) {
        object : SwingWorker<Unit, PreviewResult>() {
            override fun doInBackground() {
                for ((index, asset) in assets.withIndex()) {
                    if (isCancelled) break
                    val requestId = model.beginPreviewRequest(index)
                    val preview = try {
                        val bytes = if (variantPreviewLoader != null) {
                            variantPreviewLoader(asset, model.selectedVariant(index))
                        } else {
                            previewLoader(asset)
                        }
                        toPreviewData(bytes)
                    } catch (_: Exception) {
                        null
                    }
                    publish(PreviewResult(index, preview, requestId))
                }
            }

            override fun process(chunks: MutableList<PreviewResult>) {
                chunks.forEach { result ->
                    model.setPreview(result.index, result.preview, result.requestId)
                }
            }

            override fun done() {
                if (!isCancelled) {
                    val failed = assets.indices.count { model.previewFailed(it) }
                    status.text = if (failed == 0) "预览已加载" else "有 $failed 张预览加载失败，仍可按名称导入"
                }
            }
        }.execute()
    }

    private fun loadVariantPreview(
        model: SelectionTableModel,
        asset: LanhuDesignAsset,
        row: Int,
        previewLoader: (LanhuDesignAsset, LanhuDesignVariant?) -> ByteArray,
        status: JLabel
    ) {
        val variantUrl = model.selectedVariantUrl(row)
        val requestId = model.beginPreviewRequest(row)
        object : SwingWorker<PreviewData?, Unit>() {
            override fun doInBackground(): PreviewData? =
                runCatching {
                    toPreviewData(previewLoader(asset, model.selectedVariant(row)))
                }.getOrNull()

            override fun done() {
                val preview = runCatching { get() }.getOrNull()
                if (model.selectedVariantUrl(row) != variantUrl) return
                if (model.setPreview(row, preview, requestId)) {
                    status.text = if (preview == null) {
                        "所选分辨率预览加载失败，仍可导入"
                    } else {
                        "所选预览已加载"
                    }
                }
            }
        }.execute()
    }

    private fun toPreviewData(bytes: ByteArray): PreviewData? {
        val source = ByteArrayInputStream(bytes).use { ImageIO.read(it) } ?: return null
        if (source.width <= 0 || source.height <= 0) return null
        return PreviewData(source, toPreviewIcon(source))
    }

    private fun toPreviewIcon(source: BufferedImage): Icon {
        val scale = minOf(1.0, 72.0 / source.width, 72.0 / source.height)
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val preview = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = preview.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(source, 0, 0, width, height, null)
        graphics.dispose()
        return ImageIcon(preview)
    }

    private data class PreviewData(val image: BufferedImage, val icon: Icon)
    private data class PreviewResult(val index: Int, val preview: PreviewData?, val requestId: Int)

    private class SelectionTableModel(
        private val assets: List<LanhuDesignAsset>,
        defaultDensity: Density
    ) : AbstractTableModel() {
        private val selected = BooleanArray(assets.size) { true }
        private val densities = MutableList(assets.size) { defaultDensity }
        private val previews = arrayOfNulls<Icon>(assets.size)
        private val fullPreviews = arrayOfNulls<BufferedImage>(assets.size)
        private val columns = arrayOf("导入", "预览", "设计稿名称", "像素尺寸", "图片格式", "蓝湖分辨率", "目标目录")
        private val variants = assets.map { it.availableVariants() }
        private val formats = MutableList(assets.size) { row ->
            variants[row].firstOrNull {
                !it.format.equals("unknown", ignoreCase = true)
            }?.format?.lowercase() ?: "png"
        }
        var onChange: (() -> Unit)? = null
        var onVariantChange: ((Int) -> Unit)? = null
        private val previewRequestIds = IntArray(assets.size)

        override fun getRowCount(): Int = assets.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getColumnClass(column: Int): Class<*> =
            when (column) {
                0 -> java.lang.Boolean::class.java
                1 -> Icon::class.java
                else -> String::class.java
            }
        override fun isCellEditable(row: Int, column: Int): Boolean =
                column == 0 || column == 4 || column == 5 || column == 6
        override fun getValueAt(row: Int, column: Int): Any = when (column) {
            0 -> selected[row]
            1 -> previews[row] ?: ""
            2 -> assets[row].name
            3 -> previewDimensions(row)
            4 -> formats[row].uppercase()
            5 -> variants[row].getOrNull(selectedVariants[row])?.label ?: "-"
            else -> densities[row].resourceDirectory
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (row !in assets.indices || value == null) return
            when (column) {
                0 -> selected[row] = value as? Boolean ?: selected[row]
                4 -> setFormat(row, value.toString())
                5 -> variantIndexes(row).firstOrNull {
                    variants[row][it].label == value.toString()
                }
                    ?.let { selectedVariants[row] = it }
                6 -> Density.fromOutputDirectoryName(value.toString())?.let { density ->
                    densities[row] = density
                    variants[row].indexOfFirst {
                        it.format.equals(formats[row], ignoreCase = true) &&
                            it.sourceDensity == density
                    }
                        .takeIf { it >= 0 }
                        ?.let { selectedVariants[row] = it }
                }
            }
            fireTableCellUpdated(row, column)
            if (column == 4) {
                fireTableCellUpdated(row, 5)
            }
            if (column == 4 || column == 5 || column == 6) {
                fireTableCellUpdated(row, 3)
                onVariantChange?.invoke(row)
            }
            onChange?.invoke()
        }

        fun selectedCount(): Int = selected.count { it }

        private val selectedVariants = IntArray(assets.size) { row ->
            variants[row].indexOfFirst {
                it.format.equals(formats[row], ignoreCase = true) &&
                    it.sourceDensity == densities[row]
            }.takeIf { it >= 0 }
                ?: variants[row].indexOfFirst { it.format.equals(formats[row], ignoreCase = true) }
                .takeIf { it >= 0 } ?: 0
        }

        fun variantLabels(row: Int): Array<String> =
            variantIndexes(row).map { variants[row][it].label }
                .toTypedArray()

        fun formatLabels(row: Int): Array<String> {
            val formats = variants[row]
                .map { it.format.lowercase() }
                .filter { it != "unknown" }
                .distinct()
                .map { if (it == "jpeg") "JPG" else it.uppercase() }
            return if (formats.isEmpty()) arrayOf("PNG") else formats.toTypedArray()
        }

        private fun setFormat(row: Int, format: String) {
            formats[row] = format.lowercase()
            variantIndexes(row).firstOrNull {
                variants[row][it].sourceDensity == densities[row]
            }
                ?.let { selectedVariants[row] = it }
                ?: variantIndexes(row).firstOrNull()?.let { selectedVariants[row] = it }
        }

        private fun variantIndexes(row: Int): List<Int> {
            val exact = variants[row].indices.filter {
                variants[row][it].format.equals(formats[row], ignoreCase = true)
            }
            if (exact.isNotEmpty()) return exact
            return variants[row].indices.filter {
                variants[row][it].format == "unknown"
            }
        }

        fun beginPreviewRequest(row: Int): Int {
            if (row !in previewRequestIds.indices) return -1
            previewRequestIds[row] += 1
            return previewRequestIds[row]
        }

        fun setPreview(row: Int, icon: Icon?) {
            if (row !in previews.indices) return
            previews[row] = icon
            fullPreviews[row] = null
            fireTableCellUpdated(row, 1)
            fireTableCellUpdated(row, 3)
        }

        fun setPreview(row: Int, preview: PreviewData?, requestId: Int): Boolean {
            if (row !in previews.indices || previewRequestIds[row] != requestId) return false
            previews[row] = preview?.icon
            fullPreviews[row] = preview?.image
            fireTableCellUpdated(row, 1)
            fireTableCellUpdated(row, 3)
            return true
        }

        fun selectedVariant(row: Int): LanhuDesignVariant? =
            variants.getOrNull(row)?.getOrNull(selectedVariants.getOrNull(row) ?: -1)

        fun selectedVariantUrl(row: Int): String? = selectedVariant(row)?.url

        private fun previewDimensions(row: Int): String {
            val asset = assets[row]
            val selectedVariant = variants[row].getOrNull(selectedVariants[row])
            selectedVariant?.sourceDensity ?: return fullPreviews.getOrNull(row)?.let {
                "${it.width}×${it.height}"
            } ?: estimatedDimensions(asset, densities[row])
            val scale = selectedVariant.sourceDensity.scale
            val width = (asset.width * scale).roundToInt().coerceAtLeast(asset.width)
            val height = (asset.height * scale).roundToInt().coerceAtLeast(asset.height)
            return "${width}×${height}"
        }

        private fun estimatedDimensions(asset: LanhuDesignAsset, density: Density): String {
            val width = (asset.width * density.scale).roundToInt().coerceAtLeast(asset.width)
            val height = (asset.height * density.scale).roundToInt().coerceAtLeast(asset.height)
            return "${width}×${height}"
        }

        fun previewAt(row: Int): BufferedImage? =
            fullPreviews.getOrNull(row)

        fun previewFailed(row: Int): Boolean =
            row in previews.indices && previews[row] == null

        fun selections(): List<LanhuDesignSelection> =
            assets.indices.filter { selected[it] }.map {
                LanhuDesignSelection(
                    asset = assets[it],
                    density = densities[it],
                    variant = variants[it].getOrNull(selectedVariants[it])
                )
            }
    }

    private class PreviewCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): java.awt.Component {
            val label = JLabel().apply {
                horizontalAlignment = JLabel.CENTER
                verticalAlignment = JLabel.CENTER
                preferredSize = Dimension(76, 76)
                border = BorderFactory.createLineBorder(JBColor.border())
                icon = value as? Icon
                if (icon == null) text = "加载中…"
                background = if (isSelected) table.selectionBackground else table.background
                isOpaque = true
            }

            return label
        }
    }

    private class FormatCellEditor(
        private val model: SelectionTableModel
    ) : DefaultCellEditor(JComboBox<String>()) {
        override fun getTableCellEditorComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int
        ): java.awt.Component {
            val combo = editorComponent as JComboBox<String>
            combo.removeAllItems()
            model.formatLabels(row).forEach(combo::addItem)
            combo.selectedItem = value
            return combo
        }
    }

    private class VariantCellEditor(
        private val model: SelectionTableModel
    ) : DefaultCellEditor(JComboBox<String>()) {
        override fun getTableCellEditorComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int
        ): java.awt.Component {
            val combo = editorComponent as JComboBox<String>
            combo.removeAllItems()
            model.variantLabels(row).forEach(combo::addItem)
            combo.selectedItem = value
            return combo
        }
    }

    private class DensityCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): java.awt.Component {
            val component = super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column
            )
            if (!isSelected) component.background = JBColor(ColorValues.light, ColorValues.dark)
            (component as? JComponent)?.toolTipText = "可单独修改该图片的目标 density"
            return component
        }
    }

    private class VariantCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): java.awt.Component {
            val component = super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column
            )
            if (!isSelected) component.background = JBColor(ColorValues.light, ColorValues.dark)
            (component as? JComponent)?.toolTipText = "可单独修改蓝湖导出的分辨率"
            return component
        }
    }

    private object ColorValues {
        val light = java.awt.Color(255, 249, 218)
        val dark = java.awt.Color(76, 70, 43)
    }
}
