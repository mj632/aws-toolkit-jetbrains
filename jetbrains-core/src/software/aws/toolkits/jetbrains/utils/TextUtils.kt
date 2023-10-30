// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.utils

import com.intellij.lang.Language
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import  com.vladsch.flexmark.parser.Parser
import  com.vladsch.flexmark.html.HtmlRenderer
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanIssue

fun formatText(project: Project, language: Language, content: String): String {
    var result = content
    CommandProcessor.getInstance().runUndoTransparentAction {
        PsiFileFactory.getInstance(project)
            .createFileFromText("foo.bar", language, content, false, true)?.let {
                result = CodeStyleManager.getInstance(project).reformat(it).text
            }
    }

    return result
}

fun covertToHtml(issue: CodeWhispererCodeScanIssue): String {
    var suggestedFix = if (issue.suggestedFixes.size > 0)  issue.suggestedFixes[0] else  null
        val description = """
<div class="h3">${issue.title}</div>
<div>${convertMarkdownToHTML(issue.recommendation.text)}</div>
<hr/>
<table>
  <thead class="table-header">
    <tr>
      <td><b>Common Weakness Enumeration (CWE)</b></td>
      <td><b>Code fix available</b></td>
      <td><b>Detector library</b></td>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>
      ${issue.relatedVulnerabilities.map { cwe -> 
        "<a href=\"https://cwe.mitre.org/data/definitions/19.html\">"+ cwe +"</a>"  
        }.joinToString(", ")}
      </td>
      <td>
      ${if (suggestedFix === null) "<span class='no'>No</span>" else "<span class='yes'>Yes</span>"}
      </td>
      <td>
        <a href="https://cwe.mitre.org/data/definitions/19.html">
          ${issue.detectorName}
        </a>
      </td>
    </tr>
  </tbody>
</table>
<hr/>
""".trimIndent()

    val diffContent = renderMarkdownWithColorDiff(
        issue.suggestedFixes[0].code
    )
if(issue.suggestedFixes.size > 0) {
    val suggestedFix =  """
<div class="h4"><b>Suggested Fix</b></div>
<div>${convertMarkdownToHTML(issue.suggestedFixes[0].description)}</div>
${diffContent}
""".trimIndent()
return "$description$suggestedFix"
}
    return description
}

fun renderMarkdownWithColorDiff(markdown: String): String {


val resultHtml = processDiffContent(markdown)

return """
        <div class="code-diff-snippet">
            $resultHtml
        </div>
    """.trimIndent()
}

fun processDiffContent(diffContent: String): String {
    // Split the diff content into lines
    val lines = diffContent.split('\n')
    val regex = """@@.*@@""".toRegex()

    val processedLines = lines.map { line ->
        when {
            line.startsWith("- ") -> """
                    <div class="diff-removed">
                        <div class="code">
                            <pre>$line</pre>
                        </div>
                    </div>
                """.trimIndent()
            line.startsWith("+ ") -> """
                    <div class="diff-added">
                        <div class="code">
                            <pre>$line</pre>
                        </div>
                    </div>
                """.trimIndent()
            regex.containsMatchIn(line) -> ""
            else -> """
                    <div class="code">
                        <pre>$line</pre>
                    </div>
                """.trimIndent()
        }
    }

    return processedLines.joinToString("")
}

fun convertMarkdownToHTML(markdown: String): String {
    // val parser: Parser = Parser.builder().build()
    // val document: Node = parser.parse(markdown)
    // val htmlRenderer: HtmlRenderer = HtmlRenderer.builder().build()
    // return htmlRenderer.render(document)
    
    val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
    val parser = Parser.builder().extensions(extensions).build()
    val renderer = HtmlRenderer.builder().extensions(extensions).build()

    // Parse the Markdown text and render it to HTML
    return renderer.render(parser.parse(markdown))
}

/**
 * Designed to convert underscore separated words (e.g. UPDATE_COMPLETE) into title cased human readable text
 * (e.g. Update Complete)
 */
fun String.toHumanReadable() = StringUtil.toTitleCase(toLowerCase().replace('_', ' '))
