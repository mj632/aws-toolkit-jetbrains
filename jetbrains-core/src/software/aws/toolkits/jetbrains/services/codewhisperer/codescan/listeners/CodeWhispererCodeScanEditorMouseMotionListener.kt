// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.codescan.listeners

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager

import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.popup.AbstractPopup
import software.aws.toolkits.core.utils.debug
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanIssue
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanManager
import software.aws.toolkits.jetbrains.utils.covertToHtml
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.event.HyperlinkEvent
import javax.swing.text.Document
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet


class CodeWhispererCodeScanEditorMouseMotionListener(private val project: Project) : EditorMouseMotionListener {
    /**
     * Current context for popup is still being shown.
     */
    private var currentPopupContext: ScanIssuePopupContext? = null


    private fun hidePopup() {
        currentPopupContext?.popup?.cancel()
        currentPopupContext = null
    }

    private fun showPopup(issue: CodeWhispererCodeScanIssue?, e: EditorMouseEvent) {

        if (issue == null) {
            LOG.debug {
                "Unable to show popup issue at ${e.logicalPosition} as the issue was null"
            }
            return
        }
        val htmlString = covertToHtml(issue)
        val kit = HTMLEditorKit()

        // add some styles to the html
        val styleSheet: StyleSheet = kit.getStyleSheet()
        styleSheet.addRule(".code-diff-snippet { background-color: #2b2b2b; margin: 15px 0px;}")
        styleSheet.addRule(".diff-added {background-color: #044B53;}")
        styleSheet.addRule(".diff-removed { background-color: #632f34;}")
        styleSheet.addRule(".code { padding: 0px 15px; color: #fff;}")
        styleSheet.addRule(".yes { color: #1d8102;}")
        styleSheet.addRule(".no { color: #d13212;}")
        styleSheet.addRule("hr { margin: 5px;}")
        styleSheet.addRule(".h3 {font-size: 14px; letter-spacing: normal; margin: 0px; margin-bottom: 10px;}")
        styleSheet.addRule(".h4 {font-size: 12px; letter-spacing: normal; font-weight: 400; margin: 5px 0px;}")

        // create a document, set it on the jeditorpane, then add the html
        val doc: Document = kit.createDefaultDocument()


        val editorPane = JEditorPane().apply {
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(),
                BorderFactory.createEmptyBorder(7, 11, 8, 11)
            )
            isEditable = false
            addHyperlinkListener { he ->
                if (he.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    BrowserUtil.browse(he.url)
                }
            }
            editorKit = kit
            document = doc
            text = htmlString
        }
        val button = JButton("Apply fix")
        button.alignmentX = 0f
        button.isRolloverEnabled = true
        button.toolTipText = "Apply suggested fix"
        // TODO: add hover css
        button.addActionListener { e ->
            runInEdt {
                WriteCommandAction.runWriteCommandAction(issue.project) {
                    val document = FileDocumentManager.getInstance().getDocument(issue.file)
                    if(document !== null){
                        document.replaceString(
                            document.getLineStartOffset(19),
                            document.getLineEndOffset(22),
                            """def getInputs():
    source = sys.argv[0]
    clone = sys.argv[1]
    return source, clone
                                               """.trimIndent()
                        )
                        PsiDocumentManager.getInstance(issue.project).commitDocument(document)
                    }
                }
            }
        }
        //Lay out the buttons from left to right.
        val buttonPane = JPanel()
        buttonPane.add(button)

        val scrollPane = JBScrollPane(editorPane).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(750, 550)
        }

        val containerPane = JPanel()
        containerPane.layout = BoxLayout(containerPane, BoxLayout.PAGE_AXIS)


        containerPane.add(scrollPane)
        containerPane.add(buttonPane)



        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(containerPane, null)
            .setFocusable(true)
            .createPopup()
        // Set the currently shown issue popup context as this issue
        popup.size = (popup as AbstractPopup).preferredContentSize
        popup.content.apply {
            size = preferredSize

        }

        currentPopupContext = ScanIssuePopupContext(issue, popup)

        popup.show(RelativePoint(e.mouseEvent))
    }

    override fun mouseMoved(e: EditorMouseEvent) {
        val scanManager = CodeWhispererCodeScanManager.getInstance(project)
        if (e.area != EditorMouseEventArea.EDITING_AREA || !e.isOverText) {
            hidePopup()
            return
        }
        val offset = e.offset
        val file = FileDocumentManager.getInstance().getFile(e.editor.document)
        if (file == null) {
            LOG.error { "Cannot find file for the document ${e.editor.document}" }
            return
        }
        val issuesInRange = scanManager.getScanNodesInRange(file, offset).map {
            it.userObject as CodeWhispererCodeScanIssue
        }
        if (issuesInRange.isEmpty()) {
            hidePopup()
            return
        }
        if (issuesInRange.contains(currentPopupContext?.issue)) return

        // No popups should be visible at this point.
        hidePopup()
        // Show popup for only the first issue found.
        // Only add popup if the issue is still valid. If the issue has gone stale or invalid because
        // the user has made some edits, we don't need to show the popup for the stale or invalid issues.
        if (!issuesInRange.first().isInvalid) showPopup(issuesInRange.first(), e)
    }

    private data class ScanIssuePopupContext(val issue: CodeWhispererCodeScanIssue, val popup: JBPopup)

    companion object {
        private val LOG = getLogger<CodeWhispererCodeScanEditorMouseMotionListener>()
    }
}
