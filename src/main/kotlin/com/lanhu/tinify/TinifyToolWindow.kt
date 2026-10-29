package com.lanhu.tinify

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JTabbedPane
import javax.swing.JSeparator
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.border.LineBorder
import javax.swing.border.CompoundBorder
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer
import javax.swing.text.JTextComponent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.awt.geom.RoundRectangle2D
import javax.imageio.ImageIO
import java.util.IdentityHashMap
import com.intellij.ui.JBColor

class TinifyToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // A floating window is more useful for comparing the imported list with Android Studio.
        // Users can still dock it using the standard tool-window controls.
        runCatching { toolWindow.setType(ToolWindowType.FLOATING, null) }
        val content = ContentFactory.getInstance().createContent(TinifyPanel(project), "", false)
        toolWindow.contentManager.addContent(content)
    }
}

internal class TinifyPanel(private val project: Project) : JPanel(BorderLayout(8, 8)) {
    private companion object {
        const val FORM_LABEL_WIDTH = 140
        const val FORM_ROW_VERTICAL_INSET = 2
    }

    private val logger = Logger.getInstance(TinifyPanel::class.java)
    private val settings = com.intellij.openapi.application.ApplicationManager.getApplication()
        .getService(TinifySettingsService::class.java)
    private val densityBox = JComboBox(Density.entries.toTypedArray()).apply { selectedItem = Density.XXHDPI }
    private val lanhuDefaultDensityBox = JComboBox(Density.entries.toTypedArray()).apply {
        selectedItem = Density.XXHDPI
    }
    private val targetFolderBox = JComboBox<String>()
    private val targetDirField = JTextField(
        TargetDirectoryStore.get(project) ?: project.basePath.orEmpty(),
        30
    )
    private val apiKeyField = JPasswordField(24).apply { text = settings.getApiKey().orEmpty() }
    private val lanhuUrlField = HintTextArea(
        """
        支持项目分享链接 https://lanhuapp.com/link/#/invite?sid=...，或单页面详情链接 /web/#/item/project/detailDetach。
        单页面详情链接通常需要 pid/project_id、tid/team_id/teamId 和 image_id；缺少必要参数时会提示。
        可粘贴多条链接或包含链接的整段文本（每行一条）；不支持浏览器临时 blob: 地址。
        """.trimIndent(),
        3,
        30
    ).apply {
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(2, 7, 2, 7)
    }
    private val lanhuCookieField = JPasswordField(24).apply { text = settings.getLanhuCookie().orEmpty() }
    private val tableModel = ImageTableModel()
    private val table = JTable(tableModel)
    private val targetFolderEditor = JComboBox<String>()
    private val renameField = JTextField(ResourceNameGenerator.DEFAULT_PREFIX, 18)
    private val readLanhuButton = JButton("读取设计稿").apply {
        toolTipText = "读取蓝湖分享链接的项目，或只读取页面详情地址对应的图片"
        addActionListener { readLanhuDesigns() }
    }
    private val statusLabel = JLabel("尚未导入图片")
    private val lanhuLoginStatusLabel = JLabel("尚未保存蓝湖登录 Cookie")
    private val lanhuDesignStatusLabel = JLabel("尚未读取蓝湖设计稿")
    private var lanhuReadGeneration = 0
    private var lanhuOperationActive = false
    private val startButton = JButton("处理已导入图片")
    private val fileImportPathField = JTextField().apply {
        isEditable = false
        toolTipText = "显示最近一次选择的本地图片或 ZIP 文件路径"
    }
    private val importProgressLabel = JLabel("就绪")
    private val importCancelButton = JButton("取消导入").apply {
        isFocusPainted = false
        isVisible = false
        addActionListener {
            processingCancelRequested.set(true)
            isEnabled = false
            importProgressLabel.text = "正在取消导入…"
            statusLabel.text = "正在取消导入…"
        }
    }
    private val processingCancelButton = JButton("取消处理").apply {
        isFocusPainted = false
        isVisible = false
        addActionListener {
            processingCancelRequested.set(true)
            isEnabled = false
            statusLabel.text = "正在取消处理…"
        }
    }
    private var importPercent = 0
    private var processingPercent = 0
    private val contentPanel = JPanel(BorderLayout(8, 8)).apply { isOpaque = false }
    private val processingEnabledStates = IdentityHashMap<java.awt.Component, Boolean>()
    private val processingCancelRequested = AtomicBoolean(false)
    private var processingActive = false

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        background = JBColor(Color(248, 249, 251), Color(43, 43, 43))
        preferredSize = Dimension(1100, 935)
        minimumSize = Dimension(760, 650)
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.fillsViewportHeight = true
        table.autoCreateRowSorter = true
        table.rowHeight = 54
        table.intercellSpacing = Dimension(0, 1)
        table.gridColor = JBColor(Color(225, 228, 232), Color(70, 70, 70))
        table.selectionBackground = JBColor(Color(218, 234, 252), Color(55, 85, 120))
        table.selectionForeground = JBColor(Color(30, 45, 60), Color.WHITE)
        table.tableHeader.reorderingAllowed = false
        table.tableHeader.preferredSize = Dimension(0, 32)
        table.columnModel.getColumn(0).preferredWidth = 72
        table.columnModel.getColumn(1).preferredWidth = 180
        table.columnModel.getColumn(2).preferredWidth = 95
        table.columnModel.getColumn(3).preferredWidth = 90
        table.columnModel.getColumn(4).preferredWidth = 60
        table.columnModel.getColumn(5).preferredWidth = 180
        table.columnModel.getColumn(6).preferredWidth = 145
        table.columnModel.getColumn(7).preferredWidth = 105
        table.columnModel.getColumn(8).preferredWidth = 95
        table.columnModel.getColumn(0).cellRenderer = PreviewCellRenderer()
        table.columnModel.getColumn(5).cellRenderer = EditableCellRenderer()
        table.columnModel.getColumn(6).cellRenderer = EditableCellRenderer()
        table.columnModel.getColumn(6).cellEditor = DefaultCellEditor(
            targetFolderEditor
        )
        val booleanRenderer = BooleanTableCellRenderer()
        val booleanEditor = SingleClickBooleanCellEditor()
        table.columnModel.getColumn(7).cellRenderer = booleanRenderer
        table.columnModel.getColumn(7).cellEditor = booleanEditor
        table.columnModel.getColumn(8).cellRenderer = booleanRenderer
        table.columnModel.getColumn(8).cellEditor = booleanEditor
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount < 1 || event.button != MouseEvent.BUTTON1) return
                if (table.columnAtPoint(event.point) != 0) return
                val viewRow = table.rowAtPoint(event.point)
                if (viewRow >= 0) showImagePreview(viewRow)
            }
        })
        contentPanel.add(buildTopSection(), BorderLayout.NORTH)
        contentPanel.add(buildImageSection(), BorderLayout.CENTER)
        contentPanel.add(buildConfigurationSections(), BorderLayout.SOUTH)
        add(contentPanel, BorderLayout.CENTER)
        runCatching { Path.of(targetDirField.text) }
            .getOrNull()
            ?.let {
                tableModel.setTargetRoot(it)
            }
        refreshTargetFolderEditor()
        installTextContextMenu(targetDirField)
        installTextContextMenu(apiKeyField, allowCopy = false)
        installTextContextMenu(lanhuUrlField)
        installTextContextMenu(lanhuCookieField, allowCopy = false)
        installTextContextMenu(renameField)
        styleButtons(this)
        startButton.font = startButton.font.deriveFont(Font.BOLD)
        styleStatusLabel(lanhuLoginStatusLabel)
        styleStatusLabel(lanhuDesignStatusLabel)
        styleStatusLabel(importProgressLabel)
        styleStatusLabel(statusLabel)
        installTextContextMenu(fileImportPathField)
    }

    private fun showImagePreview(viewRow: Int) {
        val modelRow = table.convertRowIndexToModel(viewRow)
        val image = tableModel.previewImage(modelRow) ?: run {
            Messages.showInfoMessage(project, "当前图片无法解析为可预览的图像。", "图片预览")
            return
        }
        val name = tableModel.items.getOrNull(modelRow)?.originalName ?: "图片"
        val dialog = JDialog(
            SwingUtilities.getWindowAncestor(this),
            "预览：$name",
            Dialog.ModalityType.MODELESS
        )
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
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    private fun buildTopSection(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(buildHeader())
        add(javax.swing.Box.createVerticalStrut(1))
        add(buildImportSection())
        add(javax.swing.Box.createVerticalStrut(1))
        add(buildDestinationSection())
    }

    private fun buildHeader(): JPanel = JPanel(BorderLayout(8, 0)).apply {
        border = CompoundBorder(
            LineBorder(JBColor(Color(214, 220, 228), Color(75, 75, 75)),
                1, true),
            BorderFactory.createEmptyBorder(5, 12, 5, 12)
        )
        background = JBColor(Color(238, 245, 252), Color(48, 58, 70))
        isOpaque = true
        val title = JLabel("Lanhu Resource Assistant").apply {
            font = font.deriveFont(Font.BOLD, 15f)
            foreground = JBColor(Color(35, 75, 115), Color(220, 232, 245))
        }
        val subtitle = JLabel("蓝湖切图导入 · Android 资源整理 · Tinify 压缩").apply {
            foreground = JBColor(Color(80, 105, 125), Color(175, 190, 205))
        }
        add(title, BorderLayout.WEST)
        add(subtitle, BorderLayout.CENTER)
    }

    private fun buildImageSection(): JPanel = JPanel(BorderLayout(4, 4)).apply {
        border = sectionBorder("3. 图片列表")
        minimumSize = Dimension(0, 360)
        preferredSize = Dimension(0, 610)
        val tableActions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JButton("删除选中").also {
                it.toolTipText = "从当前图片列表移除选中的行，不会删除磁盘上的原文件"
                it.addActionListener { removeSelectedImages() }
            })
            add(JButton("清空列表").also {
                it.toolTipText = "移除当前图片列表中的全部行，不会删除磁盘上的原文件"
                it.addActionListener { clearImages() }
            })
            add(JSeparator(SwingConstants.VERTICAL).apply {
                preferredSize = Dimension(2, 24)
            })
            add(JLabel("目标资源目录："))
            add(targetFolderBox)
            add(JButton("应用到选中行").also {
                it.toolTipText = "将当前目标资源目录应用到选中的图片行"
                it.addActionListener { applyTargetFolder(selectedOnly = true) }
            })
            add(JButton("应用到全部").also {
                it.toolTipText = "将当前目标资源目录应用到全部图片行"
                it.addActionListener { applyTargetFolder(selectedOnly = false) }
            })
            add(JLabel("名称前缀："))
            add(renameField)
            add(JButton("应用到选中行").also {
                it.toolTipText = "将名称前缀应用到当前选中的行"
                it.addActionListener { batchRename(selectedOnly = true) }
            })
            add(JButton("应用到全部").also {
                it.toolTipText = "将名称前缀应用到表格中的全部行"
                it.addActionListener { batchRename(selectedOnly = false) }
            })
            add(hintLabel("黄色单元格可编辑"))
        }
        add(JScrollPane(table).apply {
            preferredSize = Dimension(0, 510)
            minimumSize = Dimension(400, 260)
        }, BorderLayout.CENTER)
        add(tableActions, BorderLayout.SOUTH)
    }

    private fun buildConfigurationSections(): JPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        add(buildProcessingSection())
    }

    private fun buildApiKeySection(): JPanel = JPanel(GridBagLayout()).apply {
        val c = GridBagConstraints().apply {
            insets = Insets(FORM_ROW_VERTICAL_INSET, 3, FORM_ROW_VERTICAL_INSET, 3)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        addRow(this, c, 0, "Tinify API 密钥：", JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(apiKeyField)
            add(JButton("保存密钥").also {
                it.toolTipText = "将 API 密钥安全保存到 IntelliJ PasswordSafe"
                it.addActionListener { saveKey() }
            })
            add(hintLabel("密钥安全存储于 IntelliJ PasswordSafe，不会写入项目文件或输出到日志。").withLeftSpacing(8))
        })
    }

    private fun buildDestinationSection(): JPanel = JPanel(GridBagLayout()).apply {
        border = sectionBorder("2. 目标目录")
        val c = GridBagConstraints().apply {
            insets = Insets(FORM_ROW_VERTICAL_INSET, 3, FORM_ROW_VERTICAL_INSET, 3)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        addRow(this, c, 0, "资源根目录：", JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(targetDirField)
            add(JButton("选择目录…").also {
                it.toolTipText = "选择 Android 模块根目录或 res 资源根目录；列表会读取其下的 drawable-* 和 mipmap-* 文件夹"
                it.addActionListener { chooseTargetDirectory() }
            })
            add(hintLabel("填写资源根目录；图片列表会从其下识别 drawable-* 和 mipmap-* 目录"))
        })
    }

    private fun buildImportSection(): JPanel = JPanel(BorderLayout()).apply {
        border = sectionBorder("1. 导入设置")
        add(buildImportMethods(), BorderLayout.CENTER)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(buildApiKeySection())
        }, BorderLayout.SOUTH)
    }

    private fun buildImportMethods(): JPanel {
        val cards = JPanel(CardLayout()).apply { isOpaque = false }
        cards.add(buildLanhuImportPanel(), "lanhu")
        cards.add(buildFileImportPanel(), "file")

        val lanhuTab = RoundedTabButton("蓝湖链接导入")
        val fileTab = RoundedTabButton("文件导入")
        val tabs = listOf(lanhuTab, fileTab)
        val selectTab: (RoundedTabButton, String) -> Unit = { selected, card ->
            tabs.forEach { it.selectedTab = it === selected }
            (cards.layout as CardLayout).show(cards, card)
            cards.revalidate()
            cards.repaint()
        }
        lanhuTab.addActionListener { selectTab(lanhuTab, "lanhu") }
        fileTab.addActionListener { selectTab(fileTab, "file") }
        selectTab(lanhuTab, "lanhu")

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    isOpaque = false
                    add(lanhuTab)
                    add(fileTab)
                }, BorderLayout.NORTH)
                add(JSeparator(SwingConstants.HORIZONTAL), BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(cards, BorderLayout.CENTER)
        }
    }

    private fun buildLanhuImportPanel(): JPanel = JPanel(GridBagLayout()).apply {
        val c = GridBagConstraints().apply {
            insets = Insets(FORM_ROW_VERTICAL_INSET, 3, FORM_ROW_VERTICAL_INSET, 3)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        addRow(this, c, 0, "蓝湖登录 Cookie：", JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(lanhuCookieField)
            add(JButton("保存 Cookie").also {
                it.toolTipText = "使用 IntelliJ PasswordSafe 安全保存蓝湖 Cookie"
                it.addActionListener { saveLanhuCookie() }
            })
            add(lanhuLoginStatusLabel.withLeftSpacing(8))
            add(hintLabel("需先在浏览器登录并拥有项目访问权限，再保存 Cookie。"))
        })
        addRow(this, c, 1, "蓝湖链接：", JPanel(BorderLayout(8, 0)).apply {
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(0, 4, 0, 0)
                add(JScrollPane(lanhuUrlField).apply {
                    preferredSize = Dimension(360, 58)
                    minimumSize = Dimension(300, 58)
                }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                add(fixLanhuActionButton(readLanhuButton))
                add(javax.swing.Box.createVerticalStrut(3))
                add(fixLanhuActionButton(JButton("打开链接").also {
                    it.toolTipText = "在浏览器中打开蓝湖分享链接，完成登录或输入分享密码"
                    it.addActionListener { openLanhuLink() }
                }))
                add(javax.swing.Box.createVerticalStrut(3))
                add(fixLanhuActionButton(JButton("清空").also {
                    it.toolTipText = "清空当前蓝湖设计稿地址"
                    it.addActionListener {
                        lanhuUrlField.text = ""
                        lanhuUrlField.requestFocusInWindow()
                    }
                }))
            }, BorderLayout.EAST)
        })
        addRow(this, c, 2, "设计稿读取状态：", lanhuDesignStatusLabel.withLeftSpacing(4))
        addRow(this, c, 3, "蓝湖回退 density：", lanhuDefaultDensityBox)
    }

    private fun buildFileImportPanel(): JPanel = JPanel(GridBagLayout()).apply {
        val c = GridBagConstraints().apply {
            insets = Insets(FORM_ROW_VERTICAL_INSET, 3, FORM_ROW_VERTICAL_INSET, 3)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        addRow(this, c, 0, "导入本地文件：", JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JButton("选择图片").also {
                it.toolTipText = "选择一个或多个 PNG/JPG/JPEG/WebP 文件"
                it.addActionListener { chooseImages() }
            })
            add(JButton("选择 ZIP").also {
                it.toolTipText = "选择包含图片的 ZIP 文件"
                it.addActionListener { chooseZip() }
            })
        })
        addRow(this, c, 1, "文件路径：", JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(fileImportPathField, BorderLayout.CENTER)
            add(hintLabel("选择图片或 ZIP 后，图片会显示在下方列表；ZIP 中的目录会自动识别 density。"), BorderLayout.EAST)
        })
        val statusConstraints = c.clone() as GridBagConstraints
        statusConstraints.insets = Insets(0, 3, 0, 3)
        addRow(this, statusConstraints, 2, "", JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(importProgressLabel.withLeftSpacing(8))
            add(importCancelButton)
        })
        addRow(this, c, 3, "文件回退 density：", JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(densityBox)
        })
    }

    private fun styleButtons(component: java.awt.Component) {
        if (component is JButton && component !is RoundedTabButton) {
            component.margin = Insets(5, 11, 5, 11)
            component.isFocusPainted = false
        }
        (component as? java.awt.Container)?.components?.forEach(::styleButtons)
    }

    private class RoundedTabButton(text: String) : JButton(text) {
        var selectedTab: Boolean = false
            set(value) {
                field = value
                foreground = if (value) {
                    Color.WHITE
                } else {
                    UIUtil.getLabelForeground()
                }
                repaint()
            }

        init {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            horizontalAlignment = SwingConstants.CENTER
            margin = Insets(5, 14, 5, 14)
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = UIUtil.getLabelForeground()
            val compactHeight = (preferredSize.height * 4 / 5).coerceAtLeast(20)
            margin = Insets(2, 14, 2, 14)
            preferredSize = Dimension(preferredSize.width, compactHeight)
            minimumSize = Dimension(minimumSize.width, compactHeight)
        }

        override fun paintComponent(g: Graphics) {
            val graphics = g.create() as java.awt.Graphics2D
            try {
                graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                )
                if (selectedTab) {
                    graphics.color = JBColor(Color(48, 101, 171), Color(61, 105, 160))
                    graphics.fill(
                        RoundRectangle2D.Float(
                            0f,
                            0f,
                            (width - 1).toFloat(),
                            (height - 1).toFloat(),
                            8f,
                            8f
                        )
                    )
                }
                super.paintComponent(graphics)
            } finally {
                graphics.dispose()
            }
        }

        override fun paintBorder(g: Graphics) {
            // The selected background supplies the complete rounded tab shape.
        }
    }

    private fun fixLanhuActionButton(button: JButton): JButton {
        val size = Dimension(JBUI.scale(140), JBUI.scale(30))
        return button.apply {
            preferredSize = size
            minimumSize = size
            maximumSize = size
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            if (text == "读取设计稿") {
                font = font.deriveFont(Font.BOLD)
            }
        }
    }

    private fun styleStatusLabel(label: JLabel) {
        label.foreground = JBColor(Color(63, 105, 145), Color(135, 180, 220))
        label.font = label.font.deriveFont(Font.PLAIN, 12f)
    }

    private fun JLabel.withLeftSpacing(pixels: Int): JLabel = apply {
        border = BorderFactory.createEmptyBorder(0, pixels, 0, 0)
        alignmentY = 0.5f
    }

    private fun sectionBorder(title: String): javax.swing.border.Border =
        BorderFactory.createTitledBorder(
            LineBorder(JBColor(Color(214, 220, 228), Color(75, 75, 75)), 1, true),
            title,
            javax.swing.border.TitledBorder.LEFT,
            javax.swing.border.TitledBorder.TOP,
            UIManager.getFont("Label.font").deriveFont(Font.BOLD, 12f),
            JBColor(Color(70, 90, 110), Color(200, 210, 220))
        )

    private fun installTextContextMenu(component: JTextComponent, allowCopy: Boolean = true) {
        val cutItem = JMenuItem("剪切")
        val copyItem = JMenuItem("复制")
        val pasteItem = JMenuItem("粘贴")
        val selectAllItem = JMenuItem("全选")

        cutItem.addActionListener { component.cut() }
        copyItem.addActionListener { component.copy() }
        pasteItem.addActionListener { component.paste() }
        selectAllItem.addActionListener { component.selectAll() }

        val menu = JPopupMenu().apply {
            add(cutItem)
            add(copyItem)
            add(pasteItem)
            addSeparator()
            add(selectAllItem)
            addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) {
                    val hasSelection = component.selectionStart != component.selectionEnd
                    cutItem.isEnabled = component.isEditable && hasSelection
                    copyItem.isEnabled = allowCopy && hasSelection
                    pasteItem.isEnabled = component.isEditable
                    selectAllItem.isEnabled = component.text.isNotEmpty()
                }

                override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = Unit

                override fun popupMenuCanceled(event: PopupMenuEvent) = Unit
            })
        }
        component.componentPopupMenu = menu
    }

    private fun openLanhuLink() {
        val url = normalizedLanhuUrls().firstOrNull()
        if (url == null) {
            Messages.showWarningDialog(project, "请先填写蓝湖分享链接。", "蓝湖设计稿")
            return
        }
        BrowserUtil.browse(url)
    }

    private fun saveLanhuCookie() {
        val cookie = String(lanhuCookieField.password).trim()
        if (cookie.isBlank()) {
            Messages.showWarningDialog(project, "请填写登录后的蓝湖 Cookie。", "蓝湖设计稿")
            return
        }
        settings.setLanhuCookie(cookie)
        lanhuLoginStatusLabel.text = "蓝湖登录 Cookie 已安全保存"
    }

    private fun readLanhuDesigns() {
        if (lanhuOperationActive) return
        val urls = normalizedLanhuUrls()
        val cookie = String(lanhuCookieField.password).trim().ifBlank { settings.getLanhuCookie().orEmpty() }
        if (urls.isEmpty()) {
            Messages.showWarningDialog(project, "请先填写蓝湖分享链接。", "蓝湖设计稿")
            return
        }
        if (cookie.isBlank()) {
            Messages.showWarningDialog(
                project,
                "请先在浏览器打开分享链接并完成登录/输入分享密码，再把蓝湖 Cookie 填入并保存。",
                "蓝湖设计稿需要登录"
            )
            return
        }
        settings.setLanhuCookie(cookie)
        val fallback = lanhuDefaultDensityBox.selectedItem as Density
        val state = LanhuBatchState(urls, cookie, fallback)
        val generation = ++lanhuReadGeneration
        lanhuOperationActive = true
        readLanhuButton.isEnabled = false
        lanhuDesignStatusLabel.text = "正在读取蓝湖设计稿（共 ${urls.size} 个）"
        readLanhuDesignBatch(state, generation)
    }

    private fun normalizedLanhuUrls(): List<String> {
        val rawText = lanhuUrlField.text.trim()
        val urls = LanhuLinkParser.extractUrls(rawText)
        if (urls.size == 1 && urls.first() != rawText) {
            lanhuUrlField.text = urls.first()
        }
        return urls
    }

    private data class LanhuBatchState(
        val urls: List<String>,
        val cookie: String,
        val fallback: Density
    )

    private fun readLanhuDesignBatch(state: LanhuBatchState, generation: Int) {
        val task = object : Task.Backgroundable(project, "汇总蓝湖设计稿", true) {
            val selectable = mutableListOf<LanhuBatchAsset>()
            val failures = mutableListOf<String>()
            var cancelled = false

            override fun run(indicator: ProgressIndicator) {
                state.urls.forEachIndexed { index, url ->
                    if (indicator.isCanceled) {
                        cancelled = true
                        return
                    }
                    val designStatus = "正在读取第 ${index + 1}/${state.urls.size} 个蓝湖设计稿"
                    indicator.text = designStatus
                    updateLanhuDesignStatus(generation, designStatus)
                    val client = LanhuClient(state.cookie)
                    val share: LanhuShareInfo
                    val pages: List<LanhuDesignAsset>
                    try {
                        share = client.resolveShareLink(url)
                        pages = client.listDesigns(share)
                        if (pages.isEmpty()) {
                            throw IllegalStateException("项目设计稿列表为空")
                        }
                    } catch (e: Throwable) {
                        failures += "第 ${index + 1} 个设计稿：${e.message ?: "读取失败"}"
                        return@forEachIndexed
                    }

                    val selectedPages = pages
                    selectedPages.forEachIndexed { pageIndex, page ->
                        if (indicator.isCanceled) {
                            cancelled = true
                            return
                        }
                        val pageStatus =
                            "正在读取第 ${index + 1}/${state.urls.size} 个设计稿的页面 " +
                                "${pageIndex + 1}/${selectedPages.size}"
                        indicator.text = pageStatus
                        updateLanhuDesignStatus(generation, pageStatus)
                        try {
                            val slices = client.listSlices(share, page).distinctBy { it.id }
                            if (slices.isEmpty()) {
                                selectable += LanhuBatchAsset(page, share, "设计稿 ${index + 1} · ${page.name}")
                            } else {
                                selectable += slices.map {
                                    LanhuBatchAsset(it, share, "设计稿 ${index + 1} · ${page.name}")
                                }
                            }
                        } catch (e: Throwable) {
                            failures += "第 ${index + 1} 个设计稿的页面“${page.name}”：${e.message ?: "切图读取失败"}"
                            selectable += LanhuBatchAsset(page, share, "设计稿 ${index + 1} · ${page.name}")
                        }
                    }
                }
            }

            override fun onSuccess() {
                if (cancelled) {
                    updateLanhuDesignStatus(generation, "已取消蓝湖设计稿读取")
                    return
                }
                if (selectable.isEmpty()) {
                    updateLanhuDesignStatus(generation, "蓝湖设计稿读取失败")
                    setLanhuOperationActive(false)
                    Messages.showErrorDialog(
                        project,
                        "没有读取到可导入的图片。\n\n${failures.joinToString("\n")}",
                        "蓝湖设计稿读取失败"
                    )
                    return
                }

                val failureHint = if (failures.isEmpty()) {
                    "已汇总 ${state.urls.size} 个设计稿，共 ${selectable.size} 张可导入图片。"
                } else {
                    "已汇总 ${state.urls.size} 个设计稿，共 ${selectable.size} 张可导入图片；" +
                        "以下内容读取失败但不影响其他设计稿：\n${failures.joinToString("\n")}"
                }
                updateLanhuDesignStatus(
                    generation,
                    "已汇总 ${selectable.size} 张蓝湖图片，等待统一选择"
                )
                val selections = LanhuDesignSelectionDialog.showBatch(
                    parent = this@TinifyPanel,
                    assets = selectable,
                    defaultDensity = state.fallback,
                    previewLoader = { item ->
                        LanhuClient(state.cookie).downloadPreview(item.asset, item.share)
                    },
                    variantPreviewLoader = { item, variant ->
                        LanhuClient(state.cookie).download(item.asset, variant, item.share).bytes
                    },
                    description = "$failureHint\n勾选要导入的图片，并逐项设置蓝湖分辨率和目标 Android density。"
                ) ?: run {
                    updateLanhuDesignStatus(generation, "已取消蓝湖设计稿导入")
                    setLanhuOperationActive(false)
                    return
                }

                downloadLanhuSelections(
                    generation = generation,
                    cookie = state.cookie,
                    selections = selections,
                    onComplete = { downloadFailed ->
                        val totalFailures = failures.size + if (downloadFailed) 1 else 0
                        updateLanhuDesignStatus(generation, buildString {
                            append("已处理 ${state.urls.size} 个蓝湖设计稿，导入 ${selections.size} 张图片")
                            if (totalFailures > 0) append("；部分内容失败 ${totalFailures} 项")
                        })
                        setLanhuOperationActive(false)
                    }
                )
            }

            override fun onCancel() {
                cancelled = true
                updateLanhuDesignStatus(generation, "已取消蓝湖设计稿读取")
                setLanhuOperationActive(false)
            }
        }
        task.queue()
    }

    private fun setLanhuOperationActive(active: Boolean) {
        lanhuOperationActive = active
        readLanhuButton.isEnabled = !active
    }

    private fun updateLanhuDesignStatus(generation: Int, text: String) {
        if (generation != lanhuReadGeneration) return
        if (ApplicationManager.getApplication().isDispatchThread) {
            lanhuDesignStatusLabel.text = text
        } else {
            ApplicationManager.getApplication().invokeLater {
                if (generation == lanhuReadGeneration && !project.isDisposed) {
                    lanhuDesignStatusLabel.text = text
                }
            }
        }
    }

    private fun downloadLanhuSelections(
        generation: Int,
        cookie: String,
        selections: List<LanhuDesignSelection>,
        share: LanhuShareInfo? = null,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val task = object : Task.Backgroundable(project, "下载蓝湖设计稿图片", true) {
            val images = mutableListOf<ImportedImage>()
            val skipped = mutableListOf<String>()
            val unscaled = mutableListOf<String>()
            val failures = mutableListOf<String>()
            var cancelled = false

            override fun run(indicator: ProgressIndicator) {
                val client = LanhuClient(cookie)
                selections.forEachIndexed { index, selection ->
                    if (indicator.isCanceled) {
                        cancelled = true
                        return
                    }
                    try {
                        indicator.fraction = (index + 1).toDouble() / selections.size
                        indicator.text = "步骤 6/6：下载切图 ${index + 1}/${selections.size}：${selection.asset.name}"
                        val downloaded = client.download(
                            selection.asset,
                            selection.variant,
                            selection.share ?: share
                        )
                        val format = imageFormat(downloaded)
                        val baseName = downloaded.fileName.substringBeforeLast('.', downloaded.fileName)
                        val fileName = "$baseName.$format"
                        val sourceDensity = selection.variant?.sourceDensity
                            ?: ImageDensityScaler.inferSourceDensity(
                                downloaded.bytes,
                                selection.asset.width,
                                selection.asset.height
                            )
                        val resized = ImageDensityScaler.scale(
                            bytes = downloaded.bytes,
                            format = format,
                            sourceDensity = sourceDensity,
                            targetDensity = selection.density
                        )
                        if (sourceDensity == null) {
                            unscaled += selection.asset.name
                        }
                        val image = ImageMetadata.read(resized, fileName, selection.density)
                        if (image == null) skipped += selection.asset.name else images += image
                    } catch (cancelledException: CancellationException) {
                        cancelled = true
                        return
                    } catch (e: Throwable) {
                        failures += "${selection.asset.name}：${e.message ?: "下载失败"}"
                    }
                }
            }

            override fun onSuccess() {
                if (cancelled) {
                    updateLanhuDesignStatus(generation, "已取消蓝湖切图下载")
                    setLanhuOperationActive(false)
                    return
                }
                if (images.isEmpty() && failures.isNotEmpty()) {
                    updateLanhuDesignStatus(generation, "步骤 6/6：蓝湖切图下载失败")
                    Messages.showErrorDialog(
                        project,
                        "没有成功下载任何蓝湖切图：\n${failures.joinToString("\n")}\n\n" +
                            "排查建议：如果是 401/403，请重新复制登录后的 Cookie；" +
                            "如果预览成功但下载失败，请检查蓝湖团队是否允许导出切图。",
                        "蓝湖切图下载失败"
                    )
                    onComplete(true)
                    return
                }
                tableModel.addAll(images)
                updateLanhuDesignStatus(generation, "蓝湖已导入 ${images.size} 张图片到下方列表")
                if (failures.isNotEmpty()) {
                    Messages.showWarningDialog(
                        project,
                        "以下蓝湖切图下载失败，其他图片仍已导入：\n${failures.joinToString("\n")}",
                        "蓝湖设计稿导入结果"
                    )
                }
                if (skipped.isNotEmpty()) {
                    Messages.showWarningDialog(
                        project,
                        "以下设计稿不是支持的 PNG/JPG/JPEG/WebP 图片，已跳过：\n${skipped.joinToString("\n")}",
                        "蓝湖设计稿导入结果"
                    )
                }
                if (unscaled.isNotEmpty()) {
                    Messages.showWarningDialog(
                        project,
                        "以下切图无法识别蓝湖导出 density，已保留下载到的原始像素尺寸；" +
                            "如需精确换算，请选择 PNG md/hd/xhd/xxhd/xxxhd 分辨率：\n" +
                            unscaled.joinToString("\n"),
                        "蓝湖尺寸换算提示"
                    )
                }
                onComplete(false)
            }

            override fun onCancel() {
                cancelled = true
                updateLanhuDesignStatus(generation, "已取消蓝湖切图下载")
                setLanhuOperationActive(false)
            }
        }
        task.queue()
    }

    private fun imageFormat(downloaded: LanhuDownloadedAsset): String {
        val contentType = downloaded.contentType?.substringBefore(';')?.lowercase()
        return when (contentType) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/png" -> "png"
            else -> downloaded.fileName.substringAfterLast('.', "png").lowercase()
                .let { if (it == "jpeg") "jpg" else it }
        }
    }

    private fun buildProcessingSection(): JPanel = JPanel(GridBagLayout()).apply {
        border = sectionBorder("4. 处理操作")
        val c = GridBagConstraints().apply {
            insets = Insets(FORM_ROW_VERTICAL_INSET, 3, FORM_ROW_VERTICAL_INSET, 3)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        addRow(this, c, 0, "处理操作：", JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(startButton.also {
                it.toolTipText = "仅处理勾选“导入工程”的行"
                it.addActionListener { startCompression() }
            })
            add(statusLabel.withLeftSpacing(8))
            add(processingCancelButton)
            add(hintLabel("“导入工程”是前提；未勾选时“需要压缩”失效。未压缩的图片写入原始字节；压缩失败时可回退。"))
        })
    }

    private fun beginProcessingProgress(text: String) {
        processingCancelRequested.set(false)
        setProcessingState(true)
        processingPercent = 0
        processingCancelButton.isVisible = true
        processingCancelButton.isEnabled = true
        statusLabel.text = "$text 0%"
    }

    private fun beginImportProgress(text: String) {
        processingCancelRequested.set(false)
        setProcessingState(true)
        importPercent = 0
        importCancelButton.isVisible = true
        importCancelButton.isEnabled = true
        importProgressLabel.text = "$text 0%"
        statusLabel.text = text
    }

    private fun updateImportProgress(value: Int, text: String) {
        importPercent = value.coerceIn(0, 100)
        importProgressLabel.text = "$text $importPercent%"
        styleStatusLabel(importProgressLabel)
        statusLabel.text = text
    }

    private fun finishImportProgress(value: Int, text: String) {
        updateImportProgress(value, text)
        importCancelButton.isVisible = false
        importCancelButton.isEnabled = true
        processingCancelRequested.set(false)
        setProcessingState(false)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun updateProcessingProgress(value: Int, text: String) {
        processingPercent = value.coerceIn(0, 100)
        statusLabel.text = "$text $processingPercent%"
        styleStatusLabel(statusLabel)
    }

    private fun finishProcessingProgress(value: Int, text: String) {
        updateProcessingProgress(value, text)
        processingCancelButton.isVisible = false
        processingCancelButton.isEnabled = true
        processingCancelRequested.set(false)
        setProcessingState(false)
    }

    private fun setProcessingState(active: Boolean) {
        if (processingActive == active) return
        processingActive = active
        if (active) {
            processingEnabledStates.clear()
            disableProcessingComponents(contentPanel)
            importProgressLabel.isEnabled = true
            importCancelButton.isEnabled = true
            statusLabel.isEnabled = true
            processingCancelButton.isEnabled = true
        } else {
            processingEnabledStates.forEach { (component, enabled) ->
                component.isEnabled = enabled
            }
            processingEnabledStates.clear()
            contentPanel.revalidate()
            contentPanel.repaint()
        }
    }

    private fun disableProcessingComponents(container: java.awt.Container) {
        container.components.forEach { component ->
            if (component === importProgressLabel || component === statusLabel ||
                component === importCancelButton || component === processingCancelButton
            ) {
                return@forEach
            }
            processingEnabledStates[component] = component.isEnabled
            if (component !is JPanel) {
                component.isEnabled = false
            }
            if (component is java.awt.Container) {
                disableProcessingComponents(component)
            }
        }
    }

    private fun addRow(panel: JPanel, c: GridBagConstraints, row: Int, label: String, component: java.awt.Component) {
        val labelConstraints = c.clone() as GridBagConstraints
        labelConstraints.gridx = 0
        labelConstraints.gridy = row
        labelConstraints.gridwidth = 1
        labelConstraints.weightx = 0.0
        labelConstraints.fill = GridBagConstraints.NONE
        labelConstraints.anchor = GridBagConstraints.CENTER
        panel.add(JLabel(label).apply {
            horizontalAlignment = JLabel.CENTER
            verticalAlignment = JLabel.CENTER
            border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
            val labelWidth = JBUI.scale(FORM_LABEL_WIDTH)
            preferredSize = Dimension(labelWidth, preferredSize.height)
            minimumSize = Dimension(labelWidth, preferredSize.height)
            maximumSize = Dimension(labelWidth, Int.MAX_VALUE)
        }, labelConstraints)
        val componentConstraints = c.clone() as GridBagConstraints
        componentConstraints.gridx = 1
        componentConstraints.gridy = row
        componentConstraints.gridwidth = 1
        componentConstraints.weightx = 1.0
        componentConstraints.fill = GridBagConstraints.HORIZONTAL
        componentConstraints.anchor = GridBagConstraints.CENTER
        panel.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            if (component is JPanel) {
                if (component.layout is FlowLayout) {
                    (component.layout as FlowLayout).hgap = 0
                    (component.layout as FlowLayout).vgap = 0
                }
                add(component, BorderLayout.CENTER)
            } else {
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(component)
                }, BorderLayout.WEST)
            }
        }, componentConstraints)
    }

    private fun addHintRow(panel: JPanel, c: GridBagConstraints, row: Int, text: String) {
        val hintConstraints = c.clone() as GridBagConstraints
        hintConstraints.gridx = 1
        hintConstraints.gridy = row
        hintConstraints.gridwidth = 1
        hintConstraints.weightx = 1.0
        hintConstraints.anchor = GridBagConstraints.LINE_START
        hintConstraints.fill = GridBagConstraints.HORIZONTAL
        panel.add(hintLabel(text), hintConstraints)
    }

    private fun hintLabel(text: String): JLabel = JLabel("提示：$text").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = font.deriveFont(Font.PLAIN, 12f)
        horizontalAlignment = JLabel.LEFT
        alignmentX = 0.0f
        alignmentY = 0.5f
        border = JBUI.Borders.empty(2, 8, 1, 8)
        maximumSize = Dimension(Int.MAX_VALUE, 24)
    }

    private class HintTextArea(
        private val hintText: String,
        rows: Int,
        columns: Int
    ) : JTextArea(rows, columns) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isNotEmpty()) return

            val graphics = g.create()
            try {
                graphics.color = UIUtil.getContextHelpForeground()
                graphics.font = font
                val fontMetrics = graphics.fontMetrics
                val left = insets.left
                var baseline = insets.top + fontMetrics.ascent
                hintText.lines().forEach { line ->
                    graphics.drawString(line, left, baseline)
                    baseline += fontMetrics.height
                }
            } finally {
                graphics.dispose()
            }
        }
    }

    private fun chooseImages() {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().withTitle("选择图片文件")
        val files = FileChooser.chooseFiles(descriptor, project, null)
        val selected = files.filter { it.extension?.lowercase() in setOf("png", "jpg", "jpeg", "webp") }
        updateFileImportPath(selected)
        import(selected)
    }

    private fun chooseZip() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("选择图片 ZIP 文件")
        val files = FileChooser.chooseFiles(descriptor, project, null)
        val selected = files.filter { it.extension?.lowercase() == "zip" }
        updateFileImportPath(selected)
        import(selected)
    }

    private fun updateFileImportPath(files: List<VirtualFile>) {
        fileImportPathField.text = files.joinToString("; ") { it.path }
        fileImportPathField.caretPosition = 0
    }

    private fun import(files: List<VirtualFile>) {
        if (files.isEmpty()) return
        val fallback = densityBox.selectedItem as Density
        val paths = files.mapNotNull { it.toNioPath() }
        val importStartedAt = System.nanoTime()
        logger.info("[LanhuTinify][Timing] import started: inputs=${paths.size}")
        beginImportProgress("正在准备导入图片…")
        ApplicationManager.getApplication().executeOnPooledThread {
            var result: ImageImportService.ImportResult? = null
            var failure: Throwable? = null
            var lastReportedPercent = -1
            try {
                result = ImageImportService.importFilesDetailed(
                    paths,
                    fallback,
                    onProgress = { completed, total ->
                        val percentage = if (total == 0) 0 else completed * 100 / total
                        if (percentage != lastReportedPercent || completed == total) {
                            lastReportedPercent = percentage
                            ApplicationManager.getApplication().invokeLater {
                                if (!processingCancelRequested.get()) {
                                    updateImportProgress(percentage, "正在读取导入文件（$completed/$total）…")
                                }
                            }
                        }
                    },
                    isCancelled = { processingCancelRequested.get() }
                )
                logger.info(
                    "[LanhuTinify][Timing] import worker finished: " +
                        "images=${result?.images?.size ?: 0}, skipped=${result?.skippedEntries?.size ?: 0}, " +
                        "elapsedMs=${elapsedMillis(importStartedAt)}"
                )
            } catch (e: Throwable) {
                failure = e
                logger.warn(
                    "[LanhuTinify][Timing] import worker failed after ${elapsedMillis(importStartedAt)}ms",
                    e
                )
            }

            ApplicationManager.getApplication().invokeLater {
                val uiUpdateStartedAt = System.nanoTime()
                if (processingCancelRequested.get()) {
                    finishImportProgress(importPercent, "导入已取消")
                    logger.info(
                        "[LanhuTinify][Timing] import cancelled on UI: " +
                            "uiElapsedMs=${elapsedMillis(uiUpdateStartedAt)}, totalElapsedMs=${elapsedMillis(importStartedAt)}"
                    )
                    return@invokeLater
                }
                failure?.let {
                    finishImportProgress(importPercent, "导入失败")
                    logger.info(
                        "[LanhuTinify][Timing] import failure shown on UI: " +
                            "uiElapsedMs=${elapsedMillis(uiUpdateStartedAt)}, totalElapsedMs=${elapsedMillis(importStartedAt)}"
                    )
                    Messages.showErrorDialog(project, "导入图片失败：${it.message}", "Lanhu Resource Assistant")
                    return@invokeLater
                }
                val imported = result ?: run {
                    finishImportProgress(importPercent, "导入未返回结果")
                    logger.info(
                        "[LanhuTinify][Timing] import returned no result: " +
                            "uiElapsedMs=${elapsedMillis(uiUpdateStartedAt)}, totalElapsedMs=${elapsedMillis(importStartedAt)}"
                    )
                    return@invokeLater
                }
                tableModel.addAll(imported.images)
                logger.info(
                    "[LanhuTinify][Timing] import table model updated: " +
                        "rows=${imported.images.size}, uiElapsedMs=${elapsedMillis(uiUpdateStartedAt)}"
                )
                val summary = buildString {
                    append("已导入 ${imported.images.size} 张图片")
                    if (imported.skippedEntries.isNotEmpty()) {
                        append("；已跳过 ${imported.skippedEntries.size} 个不支持或无法读取的条目")
                    }
                }
                updateImportProgress(99, "正在更新图片列表…")
                table.revalidate()
                table.repaint()
                contentPanel.revalidate()
                contentPanel.repaint()
                ApplicationManager.getApplication().invokeLater {
                    val cancelledAfterListUpdate = processingCancelRequested.get()
                    if (cancelledAfterListUpdate) {
                        finishImportProgress(importPercent, "导入已取消")
                    } else {
                        finishImportProgress(100, summary)
                    }
                    logger.info(
                        "[LanhuTinify][Timing] import UI completed: " +
                            "cancelled=$cancelledAfterListUpdate, totalElapsedMs=${elapsedMillis(importStartedAt)}"
                    )
                    if (!cancelledAfterListUpdate &&
                        imported.images.isEmpty() &&
                        imported.skippedEntries.isNotEmpty()
                    ) {
                        Messages.showWarningDialog(
                            project,
                            "未找到可读取的 PNG/JPG/JPEG/WebP 图片。AVIF 和无效图片条目不受支持。",
                            "Lanhu Resource Assistant"
                        )
                    }
                }
            }
        }
    }

    private fun chooseTargetDirectory() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
            title = "选择 Android 模块、res 或 drawable 目录"
        }
        FileChooser.chooseFile(descriptor, project, TargetDirectoryStore.initialLocation(project))?.let {
            targetDirField.text = it.path
            TargetDirectoryStore.set(project, it.path)
            tableModel.setTargetRoot(it.toNioPath())
            refreshTargetFolderEditor()
            statusLabel.text = "已选择资源根目录，并刷新图片列表目标目录"
        }
    }

    private fun refreshTargetFolderEditor() {
        val choices = tableModel.targetFolderChoices()
        targetFolderEditor.removeAllItems()
        targetFolderBox.removeAllItems()
        choices.forEach {
            targetFolderEditor.addItem(it)
            targetFolderBox.addItem(it)
        }
        if (targetFolderBox.itemCount > 0) {
            targetFolderBox.selectedItem = tableModel.items.firstOrNull()?.targetResourceFolder
                ?.takeIf { it in choices }
                ?: choices.first()
        }
    }

    private fun saveKey() {
        settings.setApiKey(String(apiKeyField.password))
        statusLabel.text = "API 密钥已安全保存"
    }

    private fun selectedModelRows(): Set<Int> =
        table.selectedRows.map { table.convertRowIndexToModel(it) }.toSet()

    private fun applyTargetFolder(selectedOnly: Boolean) {
        val rows = if (selectedOnly) selectedModelRows() else null
        if (selectedOnly && rows.isNullOrEmpty()) {
            statusLabel.text = "请先选择一行或多行"
            return
        }
        val folder = targetFolderBox.selectedItem?.toString().orEmpty()
        if (folder.isBlank()) {
            statusLabel.text = "请先选择目标资源目录"
            return
        }
        tableModel.setTargetResourceFolder(folder, rows)
    }

    private fun batchRename(selectedOnly: Boolean) {
        val prefix = renameField.text.trim()
        if (prefix.isEmpty()) return
        val rows = if (selectedOnly) selectedModelRows() else null
        if (selectedOnly && rows.isNullOrEmpty()) {
            statusLabel.text = "请先选择一行或多行"
            return
        }
        tableModel.applyPrefix(prefix, rows)
        tableModel.fireTableDataChanged()
    }

    private fun removeSelectedImages() {
        table.cellEditor?.stopCellEditing()
        val rows = selectedModelRows()
        if (rows.isEmpty()) {
            statusLabel.text = "请先选择要删除的图片"
            return
        }
        val removed = tableModel.removeRows(rows)
        table.clearSelection()
        statusLabel.text = "已从列表删除 $removed 张图片"
    }

    private fun clearImages() {
        table.cellEditor?.stopCellEditing()
        if (tableModel.items.isEmpty()) {
            statusLabel.text = "图片列表已经为空"
            return
        }
        val choice = Messages.showYesNoDialog(
            project,
            "确定清空当前图片列表吗？此操作不会删除磁盘上的原文件。",
            "清空图片列表",
            "清空",
            "取消",
            Messages.getQuestionIcon()
        )
        if (choice != Messages.YES) return
        val removed = tableModel.clear()
        table.clearSelection()
        statusLabel.text = "已清空图片列表（${removed} 张）"
    }

    private fun startCompression() {
        if (table.isEditing && table.cellEditor?.stopCellEditing() == false) {
            statusLabel.text = "目标名称编辑未完成，请确认输入后再处理"
            return
        }
        syncTargetRootFromField()
        val plan = ImageProcessingPlan.from(tableModel.items)
        if (plan.imported.isEmpty()) {
            Messages.showWarningDialog(project, "请至少勾选一行“导入工程”。", "Lanhu Resource Assistant")
            return
        }
        val base = targetDirField.text.trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { Path.of(it) }.getOrNull()
        }
        if (base == null || !Files.isDirectory(base)) {
            Messages.showWarningDialog(project, "请选择一个已存在的目标目录。", "Lanhu Resource Assistant")
            return
        }
        val errors = plan.imported.mapNotNull { image ->
            ResourceNameValidator.validate(image.targetName, image.extension(), image.targetResourceFolder)
                ?.let { "${image.originalName}: $it" }
        }
        if (errors.isNotEmpty()) {
            Messages.showErrorDialog(project, errors.joinToString("\n"), "Android 资源名称无效")
            return
        }
        val directOriginals = plan.originals.map { TinifyClient.CompressedImage(it, it.bytes.copyOf()) }
        if (plan.compressed.isEmpty()) {
            beginProcessingProgress("正在准备写入 ${plan.originals.size} 张原始图片…")
            writeResults(
                directOriginals,
                base,
                plan.originals.size,
                onProgress = { done, total ->
                    updateProcessingProgress(
                        if (total == 0) 100 else (done * 100 / total),
                        "正在写入原始图片（$done/$total）…"
                    )
                }
            )
            return
        }

        val key = settings.getApiKey() ?: String(apiKeyField.password).trim()
        if (key.isBlank()) {
            val choice = Messages.showYesNoDialog(
                project,
                "有 ${plan.compressed.size} 行已勾选“需要压缩”，但尚未配置 Tinify API 密钥。是否也将这些行写入原始字节？",
                "Tinify 不可用",
                "写入原始字节",
                "取消",
                Messages.getWarningIcon()
            )
            val fallbackOriginals = if (choice == Messages.YES) {
                plan.compressed.map { TinifyClient.CompressedImage(it, it.bytes.copyOf()) }
            } else {
                emptyList()
            }
            if (directOriginals.isNotEmpty() || fallbackOriginals.isNotEmpty()) {
                beginProcessingProgress("正在写入未压缩图片…")
                writeResults(
                    directOriginals + fallbackOriginals,
                    base,
                    plan.originals.size + fallbackOriginals.size,
                    onProgress = { done, total ->
                        updateProcessingProgress(
                            if (total == 0) 100 else (done * 100 / total),
                            "正在写入原始图片（$done/$total）…"
                        )
                    }
                )
            }
            return
        }

        beginProcessingProgress("正在连接 Tinify…")
        ApplicationManager.getApplication().executeOnPooledThread {
            var result: TinifyClient.CompressionResult? = null
            var failure: Throwable? = null
            try {
                result = TinifyClient().compressWithFailures(plan.compressed, key, { done, total ->
                    ApplicationManager.getApplication().invokeLater {
                        val percentage = if (total == 0) 75 else 5 + done * 75 / total
                        updateProcessingProgress(percentage, "正在压缩图片（$done/$total）…")
                    }
                }) { processingCancelRequested.get() }
            } catch (e: Throwable) {
                failure = e
            }

            ApplicationManager.getApplication().invokeLater {
                if (processingCancelRequested.get() || failure is CancellationException) {
                    finishProcessingProgress(processingPercent, "压缩已取消")
                    return@invokeLater
                }
                failure?.let {
                    finishProcessingProgress(processingPercent, "处理失败")
                    Messages.showErrorDialog(project, it.message ?: "压缩失败", "Lanhu Resource Assistant")
                    return@invokeLater
                }
                val completed = result ?: run {
                    finishProcessingProgress(processingPercent, "处理未返回结果")
                    return@invokeLater
                }
                updateProcessingProgress(80, "压缩完成，正在写入文件…")
                var originals = emptyList<ImportedImage>()
                if (completed.failures.isNotEmpty()) {
                    val details = completed.failures.joinToString("\n") {
                        "${it.source.originalName}：${it.error.message ?: "压缩失败"}"
                    }
                    val choice = Messages.showYesNoDialog(
                        project,
                        "Tinify 无法压缩 ${completed.failures.size} 张已勾选图片：\n$details\n\n是否改为写入这些图片的原始字节？",
                        "压缩不可用",
                        "写入原始字节",
                        "跳过失败项目",
                        Messages.getWarningIcon()
                    )
                    if (choice == Messages.YES) originals = completed.failures.map { it.source }
                }
                val outputs = completed.compressed +
                    directOriginals +
                    originals.map { TinifyClient.CompressedImage(it, it.bytes.copyOf()) }
                writeResults(
                    outputs,
                    base,
                    plan.originals.size + originals.size,
                    onProgress = { done, total ->
                        val percentage = if (total == 0) 95 else 80 + done * 15 / total
                        updateProcessingProgress(percentage, "正在写入文件（$done/$total）…")
                    }
                )
            }
        }
    }

    private fun syncTargetRootFromField() {
        val root = targetDirField.text.trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: return
        if (!Files.isDirectory(root)) return
        TargetDirectoryStore.set(project, root.toString())
        tableModel.setTargetRoot(root)
        refreshTargetFolderEditor()
    }

    private fun writeResults(
        results: List<TinifyClient.CompressedImage>,
        base: Path,
        originalCount: Int,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                writeResultsInternal(results, base, originalCount) { completed, total ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) onProgress(completed, total)
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    if (processingActive) {
                        finishProcessingProgress(processingPercent, "处理失败")
                    }
                    Messages.showErrorDialog(project, e.message ?: "写入处理失败", "Lanhu Resource Assistant")
                }
            }
        }
    }

    private fun writeResultsInternal(
        results: List<TinifyClient.CompressedImage>,
        base: Path,
        originalCount: Int,
        onProgress: (completed: Int, total: Int) -> Unit
    ) {
        val writeStartedAt = System.nanoTime()
        logger.info("[LanhuTinify][Timing] write started: results=${results.size}")
        var written = 0
        var skipped = 0
        var cancelled = false
        val writtenFiles = mutableListOf<Path>()
        val refreshedFiles = mutableListOf<VirtualFile>()
        results.forEachIndexed { index, result ->
            if (processingCancelRequested.get()) {
                cancelled = true
                return@forEachIndexed
            }
            val dir = try {
                ResourceDirectoryResolver.resolve(
                    base,
                    result.source.targetDensity,
                    targetFolder = result.source.targetResourceFolder
                )
            } catch (e: Exception) {
                showErrorDialogOnEdt("无法创建输出目录：${e.message}", "Lanhu Resource Assistant")
                onProgress(index + 1, results.size)
                return@forEachIndexed
            }
            val output = dir.resolve(result.source.targetName + "." + result.source.extension())
            if (Files.exists(output)) {
                if (!confirmOverwriteOnEdt(output)) {
                    skipped++
                    onProgress(index + 1, results.size)
                    return@forEachIndexed
                }
            }
            try {
                Files.write(output, result.bytes)
                written++
                writtenFiles.add(output)
                val refreshStartedAt = System.nanoTime()
                try {
                    val refreshedFile = refreshVirtualFileOnEdt(output)
                    refreshedFile?.let(refreshedFiles::add)
                    val refreshElapsed = elapsedMillis(refreshStartedAt)
                    if (refreshElapsed >= 100) {
                        logger.info(
                            "[LanhuTinify][Timing] VFS refresh completed: " +
                                "index=${index + 1}, elapsedMs=$refreshElapsed"
                        )
                    }
                } catch (e: Exception) {
                    logger.warn(
                        "[LanhuTinify][Timing] VFS refresh failed: index=${index + 1}, " +
                            "elapsedMs=${elapsedMillis(refreshStartedAt)}",
                        e
                    )
                }
            } catch (e: Exception) {
                showErrorDialogOnEdt("无法写入 ${output.fileName}：${e.message}", "Lanhu Resource Assistant")
            }
            onProgress(index + 1, results.size)
        }
        val gitStartedAt = System.nanoTime()
        val gitResult = if (writtenFiles.isEmpty()) {
            GitStageResult(0, null)
        } else {
            GitStageService.stage(project.basePath?.let(Path::of) ?: base, writtenFiles)
        }
        logger.info(
            "[LanhuTinify][Timing] git stage completed: files=${writtenFiles.size}, " +
                "staged=${gitResult.stagedFiles}, elapsedMs=${elapsedMillis(gitStartedAt)}"
        )
        if (refreshedFiles.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    VcsDirtyScopeManager.getInstance(project).filesDirty(refreshedFiles, emptyList())
                }
            }
            logger.info(
                "[LanhuTinify][Timing] IDE VCS refresh scheduled: files=${refreshedFiles.size}, " +
                    "elapsedMs=${elapsedMillis(writeStartedAt)}"
            )
        }
        val summary = buildString {
            if (cancelled) append("处理已取消；")
            append("已写入 $written 个文件")
            if (originalCount > 0) append("（其中 $originalCount 个为原始字节）")
            if (skipped > 0) append("；已跳过 $skipped 个")
        }
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                statusLabel.text = summary
                finishProcessingProgress(if (cancelled) processingPercent else 100, summary)
            }
        }
        logger.info(
            "[LanhuTinify][Timing] write completed: written=$written, skipped=$skipped, " +
                "cancelled=$cancelled, elapsedMs=${elapsedMillis(writeStartedAt)}"
        )
        if (written > 0) {
            val gitMessage = when {
                gitResult.stagedFiles == writtenFiles.size ->
                    "已自动执行 Git add"
                gitResult.stagedFiles > 0 ->
                    "已暂存 ${gitResult.stagedFiles} 个文件，${gitResult.message ?: "其余文件请手动添加到 Git"}"
                else ->
                    gitResult.message ?: "未能自动添加到 Git，请手动执行 Git add"
            }
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Lanhu Resource Assistant")
                        .createNotification(
                            "项目文件已更新",
                            "$summary。$gitMessage。",
                            NotificationType.INFORMATION
                        )
                        .setImportant(true)
                        .notify(project)
                }
            }
        }
    }

    private fun showErrorDialogOnEdt(message: String, title: String) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) Messages.showErrorDialog(project, message, title)
        }
    }

    private fun confirmOverwriteOnEdt(output: Path): Boolean {
        var choice = Messages.NO
        val showDialog = Runnable {
            choice = Messages.showYesNoDialog(
                project,
                "${output.fileName} 已存在。\n选择“是”覆盖，选择“否”跳过此文件并继续处理其他图片。",
                "覆盖确认",
                Messages.getQuestionIcon()
            )
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            showDialog.run()
        } else {
            ApplicationManager.getApplication().invokeAndWait(showDialog)
        }
        return choice == Messages.YES
    }

    private fun refreshVirtualFileOnEdt(output: Path): VirtualFile? {
        var refreshedFile: VirtualFile? = null
        val refresh = Runnable {
            WriteAction.run<RuntimeException> {
                refreshedFile = LocalFileSystem.getInstance()
                    .refreshAndFindFileByIoFile(output.toFile())
            }
        }
        if (ApplicationManager.getApplication().isDispatchThread) {
            refresh.run()
        } else {
            ApplicationManager.getApplication().invokeAndWait(refresh)
        }
        return refreshedFile
    }

    private fun elapsedMillis(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000

    private class EditableCellRenderer : DefaultTableCellRenderer() {
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
            if (!isSelected) {
                component.background = JBColor(
                    Color(255, 249, 218),
                    Color(76, 70, 43)
                )
            }

            (component as? JComponent)?.toolTipText = "可编辑的目标字段"
            return component
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
            val component = super.getTableCellRendererComponent(
                table,
                value,
                isSelected,
                hasFocus,
                row,
                column
            )
            horizontalAlignment = JLabel.CENTER
            verticalAlignment = JLabel.CENTER
            icon = value as? Icon
            text = if (icon == null) "—" else ""
            toolTipText = if (icon == null) "暂无预览" else "点击放大预览"
            return component
        }
    }

    internal class ImageTableModel : AbstractTableModel() {
        val items = mutableListOf<ImportedImage>()
        private var targetRoot: Path? = null
        private var targetFolders: List<String> = Density.entries.map { it.resourceDirectory }
        private val columns = arrayOf(
            "预览",
            "原文件名",
            "原始大小",
            "尺寸",
            "格式",
            "目标名称（可编辑）",
            "目标资源目录（可编辑）",
            "导入工程",
            "需要压缩"
        )
        private val previewCache = IdentityHashMap<ImportedImage, Icon?>()
        private val fullPreviewCache = IdentityHashMap<ImportedImage, BufferedImage?>()

        override fun getRowCount() = items.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getColumnClass(column: Int) =
            when {
                column == 0 -> Icon::class.java
                column == 7 || column == 8 -> java.lang.Boolean::class.java
                else -> String::class.java
            }
        override fun isCellEditable(row: Int, column: Int): Boolean =
            row in items.indices && when (column) {
                5, 6, 7 -> true
                8 -> items[row].importToProject
                else -> false
            }

        override fun getValueAt(row: Int, column: Int): Any = items[row].let { image ->
            when (column) {
                0 -> previewIcon(image) ?: ""
                1 -> image.originalName
                2 -> ImageSizeFormatter.format(image.bytes.size.toLong())
                3 -> "${image.width}×${image.height}"
                4 -> image.format.uppercase()
                5 -> image.targetName
                6 -> image.targetResourceFolder
                7 -> image.importToProject
                else -> image.compress
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (row !in items.indices || value == null) return
            when (column) {
                5 -> items[row].targetName = value.toString().trim()
                6 -> runCatching {
                    items[row].targetResourceFolder = value.toString()
                }
                7 -> (value as? Boolean)?.let {
                    items[row].importToProject = it
                    if (!it) items[row].compress = false
                }
                8 -> (value as? Boolean)?.let { items[row].compress = it }
            }
            if (column == 7) fireTableRowsUpdated(row, row) else fireTableCellUpdated(row, column)
        }

        private fun previewIcon(image: ImportedImage): Icon? =
            previewCache.getOrPut(image) {
                previewImage(image)?.let { source ->
                    val maxSize = 44
                    val scale = minOf(
                        maxSize.toDouble() / source.width,
                        maxSize.toDouble() / source.height,
                        1.0
                    )
                    val width = (source.width * scale).toInt().coerceAtLeast(1)
                    val height = (source.height * scale).toInt().coerceAtLeast(1)
                    val preview = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val graphics = preview.createGraphics()
                    try {
                        graphics.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR
                        )
                        graphics.drawImage(source, 0, 0, width, height, null)
                    } finally {
                        graphics.dispose()
                    }
                    ImageIcon(preview)
                }
            }

        fun previewImage(row: Int): BufferedImage? =
            items.getOrNull(row)?.let(::previewImage)

        private fun previewImage(image: ImportedImage): BufferedImage? =
            fullPreviewCache.getOrPut(image) {
                runCatching {
                    ByteArrayInputStream(image.bytes).use(ImageIO::read)
                }.getOrNull()
            }

        fun addAll(images: List<ImportedImage>) {
            val used = items.mapTo(mutableSetOf()) { it.targetName }
            images.map { it.freshImport() }.forEach { image ->
                image.targetResourceFolder = preferredTargetFolder(image.targetDensity)
                val base = image.targetName
                var candidate = base
                var suffix = 2
                while (!used.add(candidate)) {
                    candidate = "${base}_$suffix"
                    suffix++
                }
                image.targetName = candidate
                items += image
            }
            fireTableDataChanged()
        }

        fun setTargetResourceFolder(folder: String, rowIndexes: Set<Int>? = null) {
            val targets = rowIndexes ?: items.indices.toSet()
            targets.forEach { index ->
                items.getOrNull(index)?.let {
                    runCatching { it.targetResourceFolder = folder }
                }
            }
            fireTableDataChanged()
        }

        fun setTargetRoot(root: Path) {
            val normalizedRoot = root.toAbsolutePath().normalize()
            val rootChanged = targetRoot != normalizedRoot
            targetRoot = normalizedRoot
            targetFolders = ResourceDirectoryResolver.targetFolders(root)
            if (rootChanged) {
                items.forEach { image ->
                    image.targetResourceFolder = preferredTargetFolder(image.targetDensity)
                }
            }
            fireTableDataChanged()
        }

        fun targetFolderChoices(): List<String> = targetFolders

        private fun preferredTargetFolder(density: Density): String =
            ResourceDirectoryResolver.targetFolderForDensity(targetRoot, density)

        fun applyPrefix(prefix: String, rowIndexes: Set<Int>? = null) {
            val targets = rowIndexes ?: items.indices.toSet()
            targets.forEach { index ->
                items.getOrNull(index)?.let {
                    it.targetName = ResourceNameGenerator.applyPrefix(it.targetName, prefix)
                }
            }
        }

        fun removeRows(rowIndexes: Set<Int>): Int {
            val validRows = rowIndexes.filter { it in items.indices }.sortedDescending()
            validRows.forEach { items.removeAt(it) }
            if (validRows.isNotEmpty()) fireTableDataChanged()
            return validRows.size
        }

        fun clear(): Int {
            val removed = items.size
            if (removed > 0) {
                items.clear()
                fireTableDataChanged()
            }
            return removed
        }
    }

    internal class SingleClickBooleanCellEditor : DefaultCellEditor(
        JCheckBox().apply {
            horizontalAlignment = JCheckBox.CENTER
            isOpaque = true
        }
    ) {
        init {
            clickCountToStart = 1
        }

        override fun getTableCellEditorComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int
        ): java.awt.Component {
            val editor = super.getTableCellEditorComponent(table, value, isSelected, row, column) as JCheckBox
            editor.isSelected = value == true
            editor.background = if (isSelected) table.selectionBackground else JBColor(
                Color(232, 245, 233),
                Color(66, 82, 68)
            )
            return editor
        }
    }

    internal class BooleanTableCellRenderer : JCheckBox(), TableCellRenderer {
        init {
            horizontalAlignment = JCheckBox.CENTER
            isOpaque = true
            border = UIManager.getBorder("Table.focusCellHighlightBorder")
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): java.awt.Component {
            this.isSelected = value == true
            val modelColumn = table.convertColumnIndexToModel(column)
            val modelRow = table.convertRowIndexToModel(row)
            isEnabled = modelColumn != 7 ||
                (table.model as? ImageTableModel)?.items?.getOrNull(modelRow)?.importToProject == true
            background = if (isSelected) table.selectionBackground else JBColor(
                Color(232, 245, 233),
                Color(66, 82, 68)
            )
            foreground = if (isSelected) table.selectionForeground else table.foreground
            border = if (hasFocus) {
                UIManager.getBorder("Table.focusCellHighlightBorder")
            } else {
                BorderFactory.createEmptyBorder()
            }
            return this
        }
    }
}
