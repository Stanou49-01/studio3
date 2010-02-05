package com.aptana.editor.common.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentCommand;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;

import com.aptana.editor.common.CommonEditorPlugin;
import com.aptana.editor.common.CommonSourceViewerConfiguration;

/**
 * This implementation takes in regular expressions to match against the line to determine whether or not to indent or
 * dedent. Subclasses should pass in the regular expressions that make sense for their language, and should override the
 * abstract method to determine if we simply indent or we indent and push matching pair characters onto next line with
 * cursor in middle (i.e. for things like parens, braces, etc).
 * 
 * @author cwilliams
 */
public abstract class AbstractRegexpAutoIndentStrategy extends CommonAutoIndentStrategy
{

	private static final String SPACE_CHAR = " "; //$NON-NLS-1$
	private static final String TAB_CHAR = "\t"; //$NON-NLS-1$

	private Pattern increaseIndentRegexp;
	private Pattern decreaseIndentRegexp;

	public AbstractRegexpAutoIndentStrategy(String regexp, String decreaseRegexp, String contentType,
			SourceViewerConfiguration configuration, ISourceViewer sourceViewer)
	{
		super(contentType, configuration, sourceViewer);
		this.increaseIndentRegexp = Pattern.compile(regexp);
		if (decreaseRegexp != null)
			this.decreaseIndentRegexp = Pattern.compile(decreaseRegexp);
	}

	public AbstractRegexpAutoIndentStrategy(String regexp, String contentType, SourceViewerConfiguration configuration,
			ISourceViewer sourceViewer)
	{
		this(regexp, null, contentType, configuration, sourceViewer);
	}

	/**
	 * @param d
	 *            the document to work on
	 * @param c
	 *            the command to deal with
	 * @return true if the indentation occurred, false otherwise
	 */
	protected boolean autoIndent(IDocument d, DocumentCommand c)
	{
		if (c.offset <= 0 || d.getLength() == 0)
			return false;

		String newline = c.text;
		String indentString = TAB_CHAR;
		if (getSourceViewerConfiguration() instanceof CommonSourceViewerConfiguration)
		{
			indentString = ((CommonSourceViewerConfiguration) getSourceViewerConfiguration()).getIndent();
		}

		try
		{
			// Get the line and run a regexp check against it
			IRegion curLineRegion = d.getLineInformationOfOffset(c.offset);
			String lineContent = d.get(curLineRegion.getOffset(), c.offset - curLineRegion.getOffset());

			if (increaseIndentRegexp.matcher(lineContent).find())
			{
				String previousLineIndent = getAutoIndentAfterNewLine(d, c);
				String restOfLine = d.get(c.offset, curLineRegion.getLength() - (c.offset - curLineRegion.getOffset()));
				String startIndent = newline + previousLineIndent + indentString;
				if (indentAndPushTrailingContentAfterNewlineAndCursor(lineContent, restOfLine))
				{
					c.text = startIndent + newline + previousLineIndent;
				}
				else
				{
					c.text = startIndent;
				}
				c.shiftsCaret = false;
				c.caretOffset = c.offset + startIndent.length();
				return true;
			}
			else if (decreaseIndentRegexp != null && decreaseIndentRegexp.matcher(lineContent).find())
			{
				int lineNumber = d.getLineOfOffset(c.offset);
				if (lineNumber == 0) // first line, should be no indent yet...
				{
					return true;
				}
				int endIndex = findEndOfWhiteSpace(d, curLineRegion.getOffset(), curLineRegion.getOffset()
						+ curLineRegion.getLength());
				String currentLineIndent = d.get(curLineRegion.getOffset(), endIndex - curLineRegion.getOffset());
				if (currentLineIndent.length() == 0)
				{
					return true;
				}
				String decreasedIndent = findCorrectIndentString(d, lineNumber, currentLineIndent);
				// Shift the current line...
				int i = 0;
				while (Character.isWhitespace(lineContent.charAt(i)))
				{
					i++;
				}
				String newContent = decreasedIndent + lineContent.substring(i);
				d.replace(curLineRegion.getOffset(), curLineRegion.getLength(), newContent);
				// Set the new indent level for next line
				c.text = newline + decreasedIndent;
				c.offset = curLineRegion.getOffset() + newContent.length();
				c.shiftsCaret = false;
				return true;
			}
		}
		catch (BadLocationException e)
		{
			CommonEditorPlugin.logError(e);
		}

		return false;
	}

	/**
	 * This method determines the corrected indent string for the current line. By default this will attempt to remove
	 * one level of indent if the previous line has the same indent level or greater than the current line. Subclasses
	 * may want to do more intelligent determinations (i.e. HTML could try to find the matching open tag's indent level
	 * and use that).
	 * 
	 * @param d
	 * @param lineNumber
	 * @param currentLineIndent
	 * @return
	 * @throws BadLocationException
	 */
	protected String findCorrectIndentString(IDocument d, int lineNumber, String currentLineIndent)
			throws BadLocationException
	{
		int endIndex;
		// Grab previous line's indent level
		IRegion previousLine = d.getLineInformation(lineNumber - 1);
		endIndex = findEndOfWhiteSpace(d, previousLine.getOffset(), previousLine.getOffset() + previousLine.getLength());
		String previousLineIndent = d.get(previousLine.getOffset(), endIndex - previousLine.getOffset());

		// Try to generate a string for a decreased indent level... First, just set to previous line's indent.
		String decreasedIndent = previousLineIndent;
		if (previousLineIndent.length() >= currentLineIndent.length())
		{
			// previous indent level is same or greater than current line's, we should shift current back one
			// level
			if (previousLineIndent.endsWith(TAB_CHAR))
			{
				// Just remove the tab at end
				decreasedIndent = decreasedIndent.substring(0, decreasedIndent.length() - 1);
			}
			else
			{
				// We need to try and remove upto tab-width spaces from end, stop if we hit a tab first
				int tabWidth = guessTabWidth(d, lineNumber);
				String toRemove = decreasedIndent.substring(decreasedIndent.length() - tabWidth);
				int lastTabIndex = toRemove.lastIndexOf(TAB_CHAR);
				if (lastTabIndex != -1)
				{
					// compare last tab index to number of spaces we want to remove.
					tabWidth -= lastTabIndex + 1;
				}
				decreasedIndent = decreasedIndent.substring(0, decreasedIndent.length() - tabWidth);
			}
		}
		return decreasedIndent;
	}

	/**
	 * This method attempts to determine tab width in the file as it already exists. It checks for two indents of
	 * different sizes and returns their GCD if it's not 1. If we can't get two lines of different lenths, or their GCD
	 * is 1 then we'll fall back to using the editor's expressed tab width via the preferences.
	 * 
	 * @param d
	 * @param startLine
	 * @return
	 */
	private int guessTabWidth(IDocument d, int startLine)
	{
		try
		{
			List<Integer> lengths = new ArrayList<Integer>(3);
			for (int i = startLine; i >= 0; i--)
			{
				IRegion line = d.getLineInformation(i);
				int endofWhitespace = findEndOfWhiteSpace(d, line.getOffset(), line.getOffset() + line.getLength());
				int length = endofWhitespace - line.getOffset();
				if (length == 0)
					continue;
				// We need two different lengths to guess at tab width
				if (lengths.size() < 2 && !lengths.contains(length))
					lengths.add(length);
				if (lengths.size() >= 2)
					break;
			}
			// now we need to do a GCD of the lengths
			int tabWidth = gcd(lengths.get(0), lengths.get(1));
			if (tabWidth != 1)
				return tabWidth;
		}
		catch (BadLocationException e)
		{
			CommonEditorPlugin.logError(e);
		}

		return getTabWidth();
	}

	private int gcd(int a, int b)
	{
		if (b == 0)
			return a;
		return gcd(b, a % b);
	}

	/**
	 * Method to determine if we want to insert an indent plus another newline and initial indent. Useful for turning
	 * something like "[]" into "[\n  \n]"
	 * 
	 * @param contentBeforeNewline
	 * @param contentAfterNewline
	 * @return
	 */
	protected abstract boolean indentAndPushTrailingContentAfterNewlineAndCursor(String contentBeforeNewline,
			String contentAfterNewline);
}
