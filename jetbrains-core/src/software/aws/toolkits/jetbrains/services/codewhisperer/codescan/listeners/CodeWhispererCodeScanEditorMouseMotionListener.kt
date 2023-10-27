// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.codescan.listeners

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.popup.AbstractPopup
import software.aws.toolkits.core.utils.debug
import software.aws.toolkits.core.utils.error
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanIssue
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanManager
import software.aws.toolkits.jetbrains.utils.convertMarkdownToHTML
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.event.HyperlinkEvent


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
        var buttonClicks = 0

        if (issue == null) {
            LOG.debug {
                "Unable to show popup issue at ${e.logicalPosition} as the issue was null"
            }
            return
        }
        val description = convertMarkdownToHTML(issue.description.markdown)
        val codeFix = issue

        val editorPane = JEditorPane("text/html", description).apply {
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
        }
        val button = JButton("Apply fix")
        button.alignmentX = 0f
        button.isRolloverEnabled = true
        button.toolTipText = "Apply suggested fix"
        // TODO: add hover css
        button.addActionListener { e ->
            runInEdt {

                val document = FileDocumentManager.getInstance().getDocument(issue.file)
                println("------------------------------------------")
                println(document?.text)
                println("------------------------------------------")
                val application: Application = ApplicationManager.getApplication()
                val runnable = Runnable {
                    // your code here
                    if (document != null) {
                        val lineCount = document.lineCount
                        document.replaceString(0, lineCount,
                            """def set_user_noncompliant():
                            import os
                            root = 0
                            # set_user_noncompliance: the process user is set to root.
                            sys.argv[0]
                                            """.trimIndent())
                    }
                }
                if (application.isDispatchThread) {
                    application.runWriteAction(runnable)
                } else {
                    application.invokeLater { application.runWriteAction(runnable) }
                }




            }
            buttonClicks++
            button.text = "Apply fix $buttonClicks times clicked"
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
//            .setTitle(issue.title)
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
