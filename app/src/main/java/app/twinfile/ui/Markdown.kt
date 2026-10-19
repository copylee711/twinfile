package app.twinfile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** One rendered row of a Markdown document; nesting is flattened into [indent] and [quote]. */
private sealed interface Md {
    val indent: Int
    val quote: Int

    data class Heading(val level: Int, val text: AnnotatedString, override val quote: Int) : Md { override val indent = 0 }
    data class Para(val text: AnnotatedString, val marker: String?, override val indent: Int, override val quote: Int) : Md
    data class Code(val text: String, override val indent: Int, override val quote: Int) : Md
    data class Table(val rows: List<List<AnnotatedString>>, override val quote: Int) : Md { override val indent = 0 }
    data class Rule(override val quote: Int) : Md { override val indent = 0 }
}

private val parser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .build()

private class Flattener(private val link: Color, private val codeBg: Color) {
    val out = ArrayList<Md>()

    fun blocks(parent: Node, indent: Int, quote: Int) {
        var n = parent.firstChild
        while (n != null) {
            block(n, indent, quote, null)
            n = n.next
        }
    }

    private fun block(n: Node, indent: Int, quote: Int, marker: String?) {
        when (n) {
            is Heading -> out += Md.Heading(n.level, inline(n), quote)
            is Paragraph -> out += Md.Para(inline(n), marker, indent, quote)
            is FencedCodeBlock -> out += Md.Code(n.literal.trimEnd('\n'), indent, quote)
            is IndentedCodeBlock -> out += Md.Code(n.literal.trimEnd('\n'), indent, quote)
            is HtmlBlock -> out += Md.Code(n.literal.trimEnd('\n'), indent, quote)
            is ThematicBreak -> out += Md.Rule(quote)
            is BlockQuote -> blocks(n, indent, quote + 1)
            is BulletList -> items(n, indent + 1, quote) { "•" }
            is OrderedList -> {
                val start = n.markerStartNumber ?: 1
                items(n, indent + 1, quote) { "${start + it}." }
            }
            is TableBlock -> {
                val rows = ArrayList<List<AnnotatedString>>()
                var section = n.firstChild
                while (section != null) {
                    var row = section.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            val cells = ArrayList<AnnotatedString>()
                            var cell = row.firstChild
                            while (cell != null) {
                                if (cell is TableCell) cells += inline(cell, bold = section is TableHead)
                                cell = cell.next
                            }
                            rows += cells
                        }
                        row = row.next
                    }
                    section = section.next
                }
                out += Md.Table(rows, quote)
            }
            else -> blocks(n, indent, quote)
        }
    }

    private fun items(list: Node, indent: Int, quote: Int, marker: (Int) -> String) {
        var item = list.firstChild
        var i = 0
        while (item != null) {
            if (item is ListItem) {
                // The marker goes on the item's first block; later blocks line up under its text.
                var child = item.firstChild
                var first = true
                if (child == null) out += Md.Para(AnnotatedString(""), marker(i), indent, quote)
                while (child != null) {
                    block(child, indent, quote, if (first) marker(i) else "")
                    first = false
                    child = child.next
                }
                i++
            }
            item = item.next
        }
    }

    fun inline(parent: Node, bold: Boolean = false): AnnotatedString = buildAnnotatedString {
        fun walk(p: Node) {
            var n = p.firstChild
            while (n != null) {
                when (n) {
                    is MdText -> append(n.literal)
                    is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(" ${n.literal} ") }
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(n) }
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(n) }
                    is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { walk(n) }
                    is Link -> withLink(LinkAnnotation.Url(n.destination, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { walk(n) }
                    is Image -> withStyle(SpanStyle(color = link)) {
                        append("［图片")
                        val alt = buildString { var c = n.firstChild; while (c != null) { if (c is MdText) append(c.literal); c = c.next } }
                        if (alt.isNotEmpty()) append("：$alt")
                        append("］")
                    }
                    is SoftLineBreak -> append(' ')
                    is HardLineBreak -> append('\n')
                    is HtmlInline -> append(n.literal)
                    else -> walk(n)
                }
                n = n.next
            }
        }
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { walk(parent) } else walk(parent)
    }
}

/** Rendered Markdown: headings, lists, quotes, code, tables and links. */
@Composable
fun MarkdownView(source: String, modifier: Modifier = Modifier) {
    val twin = LocalTwin.current
    val link = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val rows = remember(source, link, codeBg) {
        Flattener(link, codeBg).apply { blocks(parser.parse(source), 0, 0) }.out
    }
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            item { Spacer(Modifier.height(8.dp)) }
            items(rows) { md ->
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    repeat(md.quote) {
                        Box(Modifier.padding(end = 10.dp).width(3.dp).fillMaxHeight().background(twin.muted.copy(alpha = 0.35f)))
                    }
                    Box(Modifier.weight(1f).padding(start = (md.indent * 14).dp)) { MdRow(md, codeBg) }
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

@Composable
private fun MdRow(md: Md, codeBg: Color) {
    val twin = LocalTwin.current
    val quoted = if (md.quote > 0) twin.muted else MaterialTheme.colorScheme.onSurface
    when (md) {
        is Md.Heading -> Column(Modifier.padding(top = if (md.level <= 2) 14.dp else 10.dp, bottom = 4.dp)) {
            Text(md.text, fontSize = when (md.level) { 1 -> 24.sp; 2 -> 20.sp; 3 -> 17.sp; else -> 15.sp },
                lineHeight = when (md.level) { 1 -> 30.sp; 2 -> 26.sp; else -> 22.sp },
                fontWeight = FontWeight.SemiBold, color = quoted)
            if (md.level <= 2) HorizontalDivider(Modifier.padding(top = 4.dp), color = twin.muted.copy(alpha = 0.2f))
        }
        is Md.Para -> Row(Modifier.padding(vertical = 3.dp)) {
            if (md.marker != null) Text(md.marker, Modifier.widthIn(min = 18.dp).padding(end = 4.dp), fontSize = 15.sp, color = twin.muted)
            Text(md.text, fontSize = 15.sp, lineHeight = 23.sp, color = quoted)
        }
        is Md.Code -> Box(
            Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(codeBg)
                .horizontalScroll(rememberScrollState()).padding(10.dp)
        ) {
            Text(md.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, softWrap = false)
        }
        is Md.Rule -> HorizontalDivider(Modifier.padding(vertical = 10.dp), color = twin.muted.copy(alpha = 0.3f))
        is Md.Table -> {
            val cols = md.rows.maxOfOrNull { it.size } ?: 0
            Column(
                Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(codeBg.copy(alpha = 0.5f))
                    .horizontalScroll(rememberScrollState())
            ) {
                md.rows.forEachIndexed { r, cells ->
                    Row(Modifier.background(if (r == 0) codeBg else Color.Transparent)) {
                        for (c in 0 until cols) {
                            Text(cells.getOrNull(c) ?: AnnotatedString(""),
                                Modifier.widthIn(min = 72.dp, max = 240.dp).padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
