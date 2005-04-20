/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.formatter;

import java.util.Arrays;
import java.util.Map;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.parser.Scanner;
import org.eclipse.jdt.internal.compiler.parser.TerminalTokens;
import org.eclipse.jdt.internal.core.util.CodeSnippetParsingUtil;
import org.eclipse.jdt.internal.core.util.RecordedParsingInformation;
import org.eclipse.jdt.internal.formatter.align.Alignment;
import org.eclipse.jdt.internal.formatter.align.AlignmentException;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

/**
 * This class is responsible for dumping formatted source
 * @since 2.1
 */
public class Scribe {
	private static final String EMPTY_STRING = ""; //$NON-NLS-1$

	private static final int INITIAL_SIZE = 100;
	
	private boolean checkLineWrapping;
	/** one-based column */
	public int column;
	private int[][] commentPositions;
		
	// Most specific alignment. 
	public Alignment currentAlignment;
	public int currentToken;
	
	// edits management
	private OptimizedReplaceEdit[] edits;
	public int editsIndex;
	
	public CodeFormatterVisitor formatter;
	public int indentationLevel;	
	public int lastNumberOfNewLines;
	public int line;
	
	private int[] lineEnds;
	private String lineSeparator;
	public Alignment memberAlignment;
	public boolean needSpace = false;
	
	public int nlsTagCounter;
	public int pageWidth;
	public boolean pendingSpace = false;

	public Scanner scanner;
	public int scannerEndPosition;
	public int tabLength;	
	public int indentationSize;	
	private int textRegionEnd;
	private int textRegionStart;
	public int tabChar;
	public int numberOfIndentations;
	private boolean useTabsOnlyForLeadingIndents;

	Scribe(CodeFormatterVisitor formatter, Map settings, int offset, int length, CodeSnippetParsingUtil codeSnippetParsingUtil) {
		if (settings != null) {
			Object sourceLevelOption = settings.get(JavaCore.COMPILER_SOURCE);
			long sourceLevel = ClassFileConstants.JDK1_3;
			if (JavaCore.VERSION_1_4.equals(sourceLevelOption)) {
				sourceLevel = ClassFileConstants.JDK1_4;
			} else if (JavaCore.VERSION_1_5.equals(sourceLevelOption)) {
				sourceLevel = ClassFileConstants.JDK1_5;
			}
			this.scanner = new Scanner(true, true, false/*nls*/, sourceLevel/*sourceLevel*/, null/*taskTags*/, null/*taskPriorities*/, true/*taskCaseSensitive*/);
		} else {
			this.scanner = new Scanner(true, true, false/*nls*/, ClassFileConstants.JDK1_3/*sourceLevel*/, null/*taskTags*/, null/*taskPriorities*/, true/*taskCaseSensitive*/);
		}
		this.formatter = formatter;
		this.pageWidth = formatter.preferences.page_width;
		this.tabLength = formatter.preferences.tab_size;
		this.indentationLevel= 0; // initialize properly
		this.numberOfIndentations = 0;
		this.useTabsOnlyForLeadingIndents = formatter.preferences.use_tabs_only_for_leading_indentations;
		this.tabChar = formatter.preferences.tab_char;
		if (this.tabChar == DefaultCodeFormatterOptions.MIXED) {
			this.indentationSize = formatter.preferences.indentation_size;
		} else {
			this.indentationSize = this.tabLength;
		}
		this.lineSeparator = formatter.preferences.line_separator;
		this.indentationLevel = formatter.preferences.initial_indentation_level * this.indentationSize;
		this.textRegionStart = offset;
		this.textRegionEnd = offset + length - 1;
		if (codeSnippetParsingUtil != null) {
			final RecordedParsingInformation information = codeSnippetParsingUtil.recordedParsingInformation;
			if (information != null) {
				this.lineEnds = information.lineEnds;
				this.commentPositions = information.commentPositions;
			}
		}
		reset();
	}
	
	private final void addDeleteEdit(int start, int end) {
		if (this.edits.length == this.editsIndex) {
			// resize
			resize();
		}
		addOptimizedReplaceEdit(start, end - start + 1, EMPTY_STRING); //$NON-NLS-1$
	}

	public final void addInsertEdit(int insertPosition, String insertedString) {
		if (this.edits.length == this.editsIndex) {
			// resize
			resize();
		}
		addOptimizedReplaceEdit(insertPosition, 0, insertedString);
	}

	private final void addOptimizedReplaceEdit(int offset, int length, String replacement) {
		if (this.editsIndex > 0) {
			// try to merge last two edits
			final OptimizedReplaceEdit previous = this.edits[this.editsIndex-1];
			final int previousOffset = previous.offset;
			final int previousLength = previous.length;
			final int endOffsetOfPreviousEdit = previousOffset + previousLength;
			final int replacementLength = replacement.length();
			final String previousReplacement = previous.replacement;
			final int previousReplacementLength = previousReplacement.length();
			if (previousOffset == offset && previousLength == length && (replacementLength == 0 || previousReplacementLength == 0)) {
				if (this.currentAlignment != null) {
					final Location location = this.currentAlignment.location;
					if (location.editsIndex == this.editsIndex) {
						location.editsIndex--;
						location.textEdit = previous;
					}
				}
				this.editsIndex--;
				return;
			}
			if (endOffsetOfPreviousEdit == offset) {
				if (length != 0) {
					if (replacementLength != 0) {
						this.edits[this.editsIndex - 1] = new OptimizedReplaceEdit(previousOffset, previousLength + length, previousReplacement + replacement);
					} else if (previousLength + length == previousReplacementLength) {
						// check the characters. If they are identical, we can get rid of the previous edit
						boolean canBeRemoved = true;
						loop: for (int i = previousOffset; i < previousOffset + previousReplacementLength; i++) {
							if (scanner.source[i] != previousReplacement.charAt(i - previousOffset)) {
								this.edits[this.editsIndex - 1] = new OptimizedReplaceEdit(previousOffset, previousReplacementLength, previousReplacement);
								canBeRemoved = false;
								break loop;
							}
						}
						if (canBeRemoved) {
							if (this.currentAlignment != null) {
								final Location location = this.currentAlignment.location;
								if (location.editsIndex == this.editsIndex) {
									location.editsIndex--;
									location.textEdit = previous;
								}
							}
							this.editsIndex--;
						}
					} else {
						this.edits[this.editsIndex - 1] = new OptimizedReplaceEdit(previousOffset, previousLength + length, previousReplacement);
					}
				} else {
					if (replacementLength != 0) {
						this.edits[this.editsIndex - 1] = new OptimizedReplaceEdit(previousOffset, previousLength, previousReplacement + replacement);
					}
				}
			} else {
				this.edits[this.editsIndex++] = new OptimizedReplaceEdit(offset, length, replacement);
			}
		} else {
			this.edits[this.editsIndex++] = new OptimizedReplaceEdit(offset, length, replacement);
		}
	}
	
	public final void addReplaceEdit(int start, int end, String replacement) {
		if (this.edits.length == this.editsIndex) {
			// resize
			resize();
		}
		addOptimizedReplaceEdit(start,  end - start + 1, replacement);
	}

	public void alignFragment(Alignment alignment, int fragmentIndex){
		alignment.fragmentIndex = fragmentIndex;
		alignment.checkColumn();
		alignment.performFragmentEffect();
	}
	
	public void checkNLSTag(int sourceStart) {
		if (hasNLSTag(sourceStart)) {
			this.nlsTagCounter++;
		}
	}
	public void consumeNextToken() {
		printComment();
		try {
			this.currentToken = this.scanner.getNextToken();
			addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
		} catch (InvalidInputException e) {
			throw new AbortFormatting(e);
		}
	}
	public Alignment createAlignment(String name, int mode, int count, int sourceRestart){
		return createAlignment(name, mode, Alignment.R_INNERMOST, count, sourceRestart);
	}

	public Alignment createAlignment(String name, int mode, int count, int sourceRestart, boolean adjust){
		return createAlignment(name, mode, Alignment.R_INNERMOST, count, sourceRestart, adjust);
	}
	
	public Alignment createAlignment(String name, int mode, int tieBreakRule, int count, int sourceRestart){
		return createAlignment(name, mode, tieBreakRule, count, sourceRestart, this.formatter.preferences.continuation_indentation, false);
	}

	public Alignment createAlignment(String name, int mode, int count, int sourceRestart, int continuationIndent, boolean adjust){
		return createAlignment(name, mode, Alignment.R_INNERMOST, count, sourceRestart, continuationIndent, adjust);
	}

	public Alignment createAlignment(String name, int mode, int tieBreakRule, int count, int sourceRestart, int continuationIndent, boolean adjust){
		Alignment alignment = new Alignment(name, mode, tieBreakRule, this, count, sourceRestart, continuationIndent);
		// adjust break indentation
		if (adjust && this.memberAlignment != null) {
			Alignment current = this.memberAlignment;
			while (current.enclosing != null) {
				current = current.enclosing;
			}
			if ((current.mode & Alignment.M_MULTICOLUMN) != 0) {
				final int indentSize = this.indentationSize;
				switch(current.chunkKind) {
					case Alignment.CHUNK_METHOD :
					case Alignment.CHUNK_TYPE :
						if ((mode & Alignment.M_INDENT_BY_ONE) != 0) {
							alignment.breakIndentationLevel = this.indentationLevel + indentSize;
						} else {
							alignment.breakIndentationLevel = this.indentationLevel + continuationIndent * indentSize;
						}
						alignment.update();
						break;
					case Alignment.CHUNK_FIELD :
						if ((mode & Alignment.M_INDENT_BY_ONE) != 0) {
							alignment.breakIndentationLevel = current.originalIndentationLevel + indentSize;
						} else {
							alignment.breakIndentationLevel = current.originalIndentationLevel + continuationIndent * indentSize;
						}
						alignment.update();
						break;
				}
			} else {
				switch(current.mode & Alignment.SPLIT_MASK) {
					case Alignment.M_COMPACT_SPLIT :
					case Alignment.M_COMPACT_FIRST_BREAK_SPLIT :
					case Alignment.M_NEXT_PER_LINE_SPLIT :
					case Alignment.M_NEXT_SHIFTED_SPLIT :
					case Alignment.M_ONE_PER_LINE_SPLIT :
						final int indentSize = this.indentationSize;
						switch(current.chunkKind) {
							case Alignment.CHUNK_METHOD :
							case Alignment.CHUNK_TYPE :
								if ((mode & Alignment.M_INDENT_BY_ONE) != 0) {
									alignment.breakIndentationLevel = this.indentationLevel + indentSize;
								} else {
									alignment.breakIndentationLevel = this.indentationLevel + continuationIndent * indentSize;
								}
								alignment.update();
								break;
							case Alignment.CHUNK_FIELD :
								if ((mode & Alignment.M_INDENT_BY_ONE) != 0) {
									alignment.breakIndentationLevel = current.originalIndentationLevel + indentSize;
								} else {
									alignment.breakIndentationLevel = current.originalIndentationLevel + continuationIndent * indentSize;
								}
								alignment.update();
								break;
						}
						break;
				}
			}
		}
		return alignment; 
	}

	public Alignment createMemberAlignment(String name, int mode, int count, int sourceRestart) {
		Alignment mAlignment = createAlignment(name, mode, Alignment.R_INNERMOST, count, sourceRestart);
		mAlignment.breakIndentationLevel = this.indentationLevel;
		return mAlignment;
	}
	
	public void enterAlignment(Alignment alignment){
		alignment.enclosing = this.currentAlignment;
		alignment.location.lastLocalDeclarationSourceStart = this.formatter.lastLocalDeclarationSourceStart;
		this.currentAlignment = alignment;
	}

	public void enterMemberAlignment(Alignment alignment) {
		alignment.enclosing = this.memberAlignment;
		alignment.location.lastLocalDeclarationSourceStart = this.formatter.lastLocalDeclarationSourceStart;
		this.memberAlignment = alignment;
	}

	public void exitAlignment(Alignment alignment, boolean discardAlignment){
		Alignment current = this.currentAlignment;
		while (current != null){
			if (current == alignment) break;
			current = current.enclosing;
		}
		if (current == null) {
			throw new AbortFormatting("could not find matching alignment: "+alignment); //$NON-NLS-1$
		}
		this.indentationLevel = alignment.location.outputIndentationLevel;
		this.numberOfIndentations = alignment.location.numberOfIndentations;
		this.formatter.lastLocalDeclarationSourceStart = alignment.location.lastLocalDeclarationSourceStart;	
		if (discardAlignment){ 
			this.currentAlignment = alignment.enclosing;
		}
	}
	
	public void exitMemberAlignment(Alignment alignment){
		Alignment current = this.memberAlignment;
		while (current != null){
			if (current == alignment) break;
			current = current.enclosing;
		}
		if (current == null) {
			throw new AbortFormatting("could not find matching alignment: "+alignment); //$NON-NLS-1$
		}
		this.indentationLevel = current.location.outputIndentationLevel;
		this.numberOfIndentations = current.location.numberOfIndentations;
		this.formatter.lastLocalDeclarationSourceStart = alignment.location.lastLocalDeclarationSourceStart;	
		this.memberAlignment = current.enclosing;
	}
	
	public Alignment getAlignment(String name){
		if (this.currentAlignment != null) {
			return this.currentAlignment.getAlignment(name);
		}
		return null;
	}
	
	/** 
	 * Answer actual indentation level based on true column position
	 * @return int
	 */
	public int getColumnIndentationLevel() {
		return this.column - 1;
	}	
	
	public final int getCommentIndex(int position) {
		if (this.commentPositions == null)
			return -1;
		int length = this.commentPositions.length;
		if (length == 0) {
			return -1;
		}
		int g = 0, d = length - 1;
		int m = 0;
		while (g <= d) {
			m = (g + d) / 2;
			int bound = this.commentPositions[m][1];
			if (bound < 0) {
				bound = -bound;
			}
			if (bound < position) {
				g = m + 1;
			} else if (bound > position) {
				d = m - 1;
			} else {
				return m;
			}
		}
		return -(g + 1);
	}

	public String getEmptyLines(int linesNumber) {
		if (this.nlsTagCounter > 0) {
			return EMPTY_STRING;
		}
		StringBuffer buffer = new StringBuffer();
		if (lastNumberOfNewLines == 0) {
			linesNumber++; // add an extra line breaks
			for (int i = 0; i < linesNumber; i++) {
				buffer.append(this.lineSeparator);
			}
			lastNumberOfNewLines += linesNumber;
			line += linesNumber;
			column = 1;
			needSpace = false;
			this.pendingSpace = false;
		} else if (lastNumberOfNewLines == 1) {
			for (int i = 0; i < linesNumber; i++) {
				buffer.append(this.lineSeparator);
			}
			lastNumberOfNewLines += linesNumber;
			line += linesNumber;
			column = 1;
			needSpace = false;
			this.pendingSpace = false;
		} else {
			if ((lastNumberOfNewLines - 1) >= linesNumber) {
				// there is no need to add new lines
				return EMPTY_STRING;
			}
			final int realNewLineNumber = linesNumber - lastNumberOfNewLines + 1;
			for (int i = 0; i < realNewLineNumber; i++) {
				buffer.append(this.lineSeparator);
			}
			lastNumberOfNewLines += realNewLineNumber;
			line += realNewLineNumber;
			column = 1;
			needSpace = false;
			this.pendingSpace = false;
		}
		return String.valueOf(buffer);
	}

	public OptimizedReplaceEdit getLastEdit() {
		if (this.editsIndex > 0) {
			return this.edits[this.editsIndex - 1];
		}
		return null;
	}
	
	public final int getLineEnd(int lineNumber) {
		if (this.lineEnds == null) 
			return -1;
		if (lineNumber >= this.lineEnds.length + 1) 
			return this.scannerEndPosition;
		if (lineNumber <= 0) 
			return -1;
		return this.lineEnds[lineNumber-1]; // next line start one character behind the lineEnd of the previous line	
	}
	
	Alignment getMemberAlignment() {
		return this.memberAlignment;
	}
	
	public String getNewLine() {
		if (this.nlsTagCounter > 0) {
			return EMPTY_STRING;
		}
		if (lastNumberOfNewLines >= 1) {
			column = 1; // ensure that the scribe is at the beginning of a new line
			return EMPTY_STRING;
		}
		line++;
		lastNumberOfNewLines = 1;
		column = 1;
		needSpace = false;
		this.pendingSpace = false;
		return this.lineSeparator;
	}

	/** 
	 * Answer next indentation level based on column estimated position
	 * (if column is not indented, then use indentationLevel)
	 */
	public int getNextIndentationLevel(int someColumn) {
		int indent = someColumn - 1;
		if (indent == 0)
			return this.indentationLevel;
		if (this.tabChar == DefaultCodeFormatterOptions.TAB) {
			if (this.useTabsOnlyForLeadingIndents) {
				return indent;
			}
			int rem = indent % this.indentationSize;
			int addition = rem == 0 ? 0 : this.indentationSize - rem; // round to superior
			return indent + addition;
		} else {
			return indent;
		}
	}

	private String getPreserveEmptyLines(int count) {
		if (count > 0) {
			if (this.formatter.preferences.number_of_empty_lines_to_preserve != 0) {
				int linesToPreserve = Math.min(count, this.formatter.preferences.number_of_empty_lines_to_preserve);
				return this.getEmptyLines(linesToPreserve);
			} else {
				return getNewLine();
			}
		}
		return EMPTY_STRING;
	}
	
	public TextEdit getRootEdit() {
		MultiTextEdit edit = null;
		int length = this.textRegionEnd - this.textRegionStart + 1;
		if (this.textRegionStart <= 0) {
			if (length <= 0) {
				edit = new MultiTextEdit(0, 0);
			} else {
				edit = new MultiTextEdit(0, this.textRegionEnd + 1);
			}
		} else {
			edit = new MultiTextEdit(this.textRegionStart, this.textRegionEnd - this.textRegionStart + 1);
		}
		for (int i= 0, max = this.editsIndex; i < max; i++) {
			OptimizedReplaceEdit currentEdit = edits[i];
			if (isValidEdit(currentEdit)) {
				edit.addChild(new ReplaceEdit(currentEdit.offset, currentEdit.length, currentEdit.replacement));
			}
		}
		this.edits = null;
		return edit;
	}
	
	public void handleLineTooLong() {
		// search for closest breakable alignment, using tiebreak rules
		// look for outermost breakable one
		int relativeDepth = 0, outerMostDepth = -1;
		Alignment targetAlignment = this.currentAlignment;
		while (targetAlignment != null){
			if (targetAlignment.tieBreakRule == Alignment.R_OUTERMOST && targetAlignment.couldBreak()){
				outerMostDepth = relativeDepth;
			}
			targetAlignment = targetAlignment.enclosing;
			relativeDepth++;
		}
		if (outerMostDepth >= 0) {
			throw new AlignmentException(AlignmentException.LINE_TOO_LONG, outerMostDepth);
		}
		// look for innermost breakable one
		relativeDepth = 0;
		targetAlignment = this.currentAlignment;
		while (targetAlignment != null){
			if (targetAlignment.couldBreak()){
				throw new AlignmentException(AlignmentException.LINE_TOO_LONG, relativeDepth);
			}
			targetAlignment = targetAlignment.enclosing;
			relativeDepth++;
		}
		// did not find any breakable location - proceed
	}

	/*
	 * Check if there is a NLS tag on this line. If yes, return true, returns false otherwise.
	 */
	private boolean hasNLSTag(int sourceStart) {
		// search the last comment where commentEnd < current lineEnd
		if (this.lineEnds == null) return false;
		int index = Arrays.binarySearch(this.lineEnds, sourceStart);
		int currentLineEnd = this.getLineEnd(-index);
		if (currentLineEnd != -1) {
			int commentIndex = getCommentIndex(currentLineEnd);
			if (commentIndex < 0) {
				commentIndex = -commentIndex - 2;
			}
			if (commentIndex >= 0 && commentIndex < this.commentPositions.length) {
				int start = this.commentPositions[commentIndex][0];
				if (start < 0) {
					start = -start;
					// check that we are on the same line
					int lineIndexForComment = Arrays.binarySearch(this.lineEnds, start);
					if (lineIndexForComment == index) {
						return indexOf(Scanner.TAG_PREFIX.toCharArray(), this.scanner.source, start, currentLineEnd) != -1;
					}
				}
			}
		}
		return false;
	}
	
	public void indent() {
		this.indentationLevel += this.indentationSize;
		this.numberOfIndentations++;
	}	

	private int indexOf(char[] toBeFound, char[] source, int start, int end) {
		if (toBeFound == null || source == null) {
			throw new IllegalArgumentException();
		}
		int toBeFoundLength = toBeFound.length;
		if (end < start || (end - start + 1) < toBeFoundLength) {
			return -1;
		}
		int indexInSource = 0;
		for (int i = start; i < end; i++) {
			if (source[i] == toBeFound[indexInSource]) {
				int j = i + 1;
				indexInSource++;
				loop: for (; j < end && indexInSource < toBeFoundLength; j++) {
					if (toBeFound[indexInSource] != source[j]) {
						break loop;
					}
					indexInSource++;
				}
				if (j == i + toBeFoundLength) {
					return i;
				}
				indexInSource = 0;
			}
		}
		return -1;
	}
	/**
	 * @param compilationUnitSource
	 */
	public void initializeScanner(char[] compilationUnitSource) {
		this.scanner.setSource(compilationUnitSource);
		this.scannerEndPosition = compilationUnitSource.length;
		this.scanner.resetTo(0, this.scannerEndPosition);
		this.edits = new OptimizedReplaceEdit[INITIAL_SIZE];
	}	
	
	private boolean isValidEdit(OptimizedReplaceEdit edit) {
		final int editLength= edit.length;
		final int editReplacementLength= edit.replacement.length();
		final int editOffset= edit.offset;
		if (editLength != 0) {
			if (this.textRegionStart <= editOffset && (editOffset + editLength - 1) <= this.textRegionEnd) {
				if (editReplacementLength != 0 && editLength == editReplacementLength) {
					for (int i = editOffset, max = editOffset + editLength; i < max; i++) {
						if (scanner.source[i] != edit.replacement.charAt(i - editOffset)) {
							return true;
						}
					}
					return false;
				} else {
					return true;
				}
			} else if (editOffset + editLength == this.textRegionStart) {
				int i = editOffset;
				for (int max = editOffset + editLength; i < max; i++) {
					int replacementStringIndex = i - editOffset;
					if (replacementStringIndex >= editReplacementLength || scanner.source[i] != edit.replacement.charAt(replacementStringIndex)) {
						break;
					}
				}
				if (i - editOffset != editReplacementLength && i != editOffset + editLength - 1) {
					edit.offset = textRegionStart;
					edit.length = 0;
					edit.replacement = edit.replacement.substring(i - editOffset);
					return true;
				}
			}
		} else if (this.textRegionStart <= editOffset && editOffset <= this.textRegionEnd) {
			return true;
		} else if (editOffset == this.scannerEndPosition && editOffset == this.textRegionEnd + 1) {
			return true;
		}
		return false;
	}

	private void preserveEmptyLines(int count, int insertPosition) {
		if (count > 0) {
			if (this.formatter.preferences.number_of_empty_lines_to_preserve != 0) {
				int linesToPreserve = Math.min(count, this.formatter.preferences.number_of_empty_lines_to_preserve);
				this.printEmptyLines(linesToPreserve, insertPosition);
			} else {
				printNewLine(insertPosition);
			}
		}
	}

	private void print(char[] s, boolean considerSpaceIfAny) {
		if (checkLineWrapping && s.length + column > this.pageWidth) {
			handleLineTooLong();
		}
		this.lastNumberOfNewLines = 0;
		printIndentationIfNecessary();
		if (considerSpaceIfAny) {
			this.space();
		}
		if (this.pendingSpace) {
			this.addInsertEdit(this.scanner.getCurrentTokenStartPosition(), " "); //$NON-NLS-1$
		}
		this.pendingSpace = false;	
		this.needSpace = false;		
		column += s.length;
		needSpace = true;
	}

	private void printBlockComment(char[] s, boolean isJavadoc) {
		int currentTokenStartPosition = this.scanner.getCurrentTokenStartPosition();
		int currentTokenEndPosition = this.scanner.getCurrentTokenEndPosition() + 1;
		
		this.scanner.resetTo(currentTokenStartPosition, currentTokenEndPosition - 1);
		int currentCharacter;
		boolean isNewLine = false;
		int start = currentTokenStartPosition;
		int nextCharacterStart = currentTokenStartPosition;
		printIndentationIfNecessary();
		if (this.pendingSpace) {
			this.addInsertEdit(currentTokenStartPosition, " "); //$NON-NLS-1$
		}
		this.needSpace = false;		
		this.pendingSpace = false;		
		int previousStart = currentTokenStartPosition;

		while (nextCharacterStart <= currentTokenEndPosition && (currentCharacter = this.scanner.getNextChar()) != -1) {
			nextCharacterStart = this.scanner.currentPosition;

			switch(currentCharacter) {
				case '\r' :
					start = previousStart;
					isNewLine = true;
					if (this.scanner.getNextChar('\n')) {
						currentCharacter = '\n';
						nextCharacterStart = this.scanner.currentPosition;
					}
					break;
				case '\n' :
					start = previousStart;
					isNewLine = true;
					break;
				default:
					if (isNewLine) {
						if (Character.isWhitespace((char) currentCharacter)) {
							int previousStartPosition = this.scanner.currentPosition;
							while(currentCharacter != -1 && currentCharacter != '\r' && currentCharacter != '\n' && Character.isWhitespace((char) currentCharacter)) {
								previousStart = nextCharacterStart;
								previousStartPosition = this.scanner.currentPosition;
								currentCharacter = this.scanner.getNextChar();
								nextCharacterStart = this.scanner.currentPosition;
							}
							if (currentCharacter == '\r' || currentCharacter == '\n') {
								nextCharacterStart = previousStartPosition;
							}
						}
						this.column = 1;
						this.line++;

						StringBuffer buffer = new StringBuffer();
						buffer.append(this.lineSeparator);
						printIndentationIfNecessary(buffer);
						buffer.append(' ');
				
						addReplaceEdit(start, previousStart - 1, String.valueOf(buffer));
					} else {
						this.column += (nextCharacterStart - previousStart);
					}
					isNewLine = false;
			}
			previousStart = nextCharacterStart;
			this.scanner.currentPosition = nextCharacterStart;
		}
		this.lastNumberOfNewLines = 0;
		needSpace = false;
		this.scanner.resetTo(currentTokenEndPosition, this.scannerEndPosition - 1);
		if (isJavadoc) {
			printNewLine();
		}
	}
	
	public void printEndOfCompilationUnit() {
		try {
			// if we have a space between two tokens we ensure it will be dumped in the formatted string
			int currentTokenStartPosition = this.scanner.currentPosition;
			boolean hasComment = false;
			boolean hasLineComment = false;
			boolean hasWhitespace = false;
			int count = 0;
			while (true) {
				this.currentToken = this.scanner.getNextToken();
				switch(this.currentToken) {
					case TerminalTokens.TokenNameWHITESPACE :
						char[] whiteSpaces = this.scanner.getCurrentTokenSource();
						count = 0;
						for (int i = 0, max = whiteSpaces.length; i < max; i++) {
							switch(whiteSpaces[i]) {
								case '\r' :
									if ((i + 1) < max) {
										if (whiteSpaces[i + 1] == '\n') {
											i++;
										}
									}
									count++;
									break;
								case '\n' :
									count++;
							}
						}
						if (count == 0) {
							hasWhitespace = true;
							addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
						} else if (hasComment) {
							if (count == 1) {
								this.printNewLine(this.scanner.getCurrentTokenStartPosition());
							} else {
								preserveEmptyLines(count - 1, this.scanner.getCurrentTokenStartPosition());
							}
							addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
						} else if (hasLineComment) {
							this.preserveEmptyLines(count, this.scanner.getCurrentTokenStartPosition());
							addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
						} else {
							addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
						}
						currentTokenStartPosition = this.scanner.currentPosition;						
						break;
					case TerminalTokens.TokenNameCOMMENT_LINE :
						if (count >= 1) {
							if (count > 1) {
								preserveEmptyLines(count - 1, this.scanner.getCurrentTokenStartPosition());
							} else if (count == 1) {
								printNewLine(this.scanner.getCurrentTokenStartPosition());
							}
						} else if (hasWhitespace) {
							space();
						} 
						hasWhitespace = false;
						this.printCommentLine(this.scanner.getRawTokenSource());
						currentTokenStartPosition = this.scanner.currentPosition;
						hasLineComment = true;		
						count = 0;
						break;
					case TerminalTokens.TokenNameCOMMENT_BLOCK :
						if (count >= 1) {
							if (count > 1) {
								preserveEmptyLines(count - 1, this.scanner.getCurrentTokenStartPosition());
							} else if (count == 1) {
								printNewLine(this.scanner.getCurrentTokenStartPosition());
							}
						} else if (hasWhitespace) {
							space();
						} 
						hasWhitespace = false;
						this.printBlockComment(this.scanner.getRawTokenSource(), false);
						currentTokenStartPosition = this.scanner.currentPosition;
						hasLineComment = false;
						hasComment = true;
						count = 0;
						break;
					case TerminalTokens.TokenNameCOMMENT_JAVADOC :
						if (count >= 1) {
							if (count > 1) {
								preserveEmptyLines(count - 1, this.scanner.getCurrentTokenStartPosition());
							} else if (count == 1) {
								printNewLine(this.scanner.getCurrentTokenStartPosition());
							}
						} else if (hasWhitespace) {
							space();
						} 
						hasWhitespace = false;
						this.printBlockComment(this.scanner.getRawTokenSource(), true);
						currentTokenStartPosition = this.scanner.currentPosition;
						hasLineComment = false;
						hasComment = true;
						count = 0;
						break;
					case TerminalTokens.TokenNameSEMICOLON :
						char[] currentTokenSource = this.scanner.getRawTokenSource();
						this.print(currentTokenSource, this.formatter.preferences.insert_space_before_semicolon);
						break;
					case TerminalTokens.TokenNameEOF :
						if (count >= 1 || this.formatter.preferences.insert_new_line_at_end_of_file_if_missing) {
							this.printNewLine(this.scannerEndPosition);
						}
						return;
					default :
						// step back one token
						this.scanner.resetTo(currentTokenStartPosition, this.scannerEndPosition - 1);
						return;
				}
			}
		} catch (InvalidInputException e) {
			throw new AbortFormatting(e);
		}
	}

	public void printComment() {
		try {
			// if we have a space between two tokens we ensure it will be dumped in the formatted string
			int currentTokenStartPosition = this.scanner.currentPosition;
			boolean hasComment = false;
			boolean hasLineComment = false;
			boolean hasWhitespace = false;
			int count = 0;
			while ((this.currentToken = this.scanner.getNextToken()) != TerminalTokens.TokenNameEOF) {
				switch(this.currentToken) {
					case TerminalTokens.TokenNameWHITESPACE :
						char[] whiteSpaces = this.scanner.getCurrentTokenSource();
						count = 0;
						for (int i = 0, max = whiteSpaces.length; i < max; i++) {
							switch(whiteSpaces[i]) {
								case '\r' :
									if ((i + 1) < max) {
										if (whiteSpaces[i + 1] == '\n') {
											i++;
										}
									}
									count++;
									break;
								case '\n' :
									count++;
							}
						}
						if (count == 0) {
							hasWhitespace = true;
							addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
						} else if (hasComment) {
							if (count == 1) {
								this.printNewLine(this.scanner.getCurrentTokenStartPosition());
							} else {
								preserveEmptyLines(count - 1, this.scanner.getCurrentTokenStartPosition());
							}
							addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
						} else if (hasLineComment) {
							this.preserveEmptyLines(count, this.scanner.getCurrentTokenStartPosition());
							addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
						} else if (count != 0 && this.formatter.preferences.number_of_empty_lines_to_preserve != 0) {
							addReplaceEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition(), this.getPreserveEmptyLines(count - 1));
						} else {
							addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
						}
						currentTokenStartPosition = this.scanner.currentPosition;						
						break;
					case TerminalTokens.TokenNameCOMMENT_LINE :
						if (count >= 1) {
							if (count > 1) {
								preserveEmptyLines(count - 1, this.scanner.getCurrentTokenStartPosition());
							} else if (count == 1) {
								printNewLine(this.scanner.getCurrentTokenStartPosition());
							}
						} else if (hasWhitespace) {
							space();
						} 
						hasWhitespace = false;
						this.printCommentLine(this.scanner.getRawTokenSource());
						currentTokenStartPosition = this.scanner.currentPosition;
						hasLineComment = true;		
						count = 0;
						break;
					case TerminalTokens.TokenNameCOMMENT_BLOCK :
						if (count >= 1) {
							if (count > 1) {
								preserveEmptyLines(count - 1, this.scanner.getCurrentTokenStartPosition());
							} else if (count == 1) {
								printNewLine(this.scanner.getCurrentTokenStartPosition());
							}
						} else if (hasWhitespace) {
							space();
						} 
						hasWhitespace = false;
						this.printBlockComment(this.scanner.getRawTokenSource(), false);
						currentTokenStartPosition = this.scanner.currentPosition;
						hasLineComment = false;
						hasComment = true;
						count = 0;
						break;
					case TerminalTokens.TokenNameCOMMENT_JAVADOC :
						if (count >= 1) {
							if (count > 1) {
								preserveEmptyLines(count - 1, this.scanner.getCurrentTokenStartPosition());
							} else if (count == 1) {
								printNewLine(this.scanner.getCurrentTokenStartPosition());
							}
						} else if (hasWhitespace) {
							space();
						} 
						hasWhitespace = false;
						this.printBlockComment(this.scanner.getRawTokenSource(), true);
						currentTokenStartPosition = this.scanner.currentPosition;
						hasLineComment = false;
						hasComment = true;
						count = 0;
						break;
					default :
						// step back one token
						this.scanner.resetTo(currentTokenStartPosition, this.scannerEndPosition - 1);
						return;
				}
			}
		} catch (InvalidInputException e) {
			throw new AbortFormatting(e);
		}
	}
	
	private void printCommentLine(char[] s) {
		int currentTokenStartPosition = this.scanner.getCurrentTokenStartPosition();
		int currentTokenEndPosition = this.scanner.getCurrentTokenEndPosition() + 1;
		if (indexOf(Scanner.TAG_PREFIX.toCharArray(), this.scanner.source, currentTokenStartPosition, currentTokenEndPosition) != -1) {
			this.nlsTagCounter = 0;
		}
		this.scanner.resetTo(currentTokenStartPosition, currentTokenEndPosition - 1);
		int currentCharacter;
		int start = currentTokenStartPosition;
		int nextCharacterStart = currentTokenStartPosition;
		printIndentationIfNecessary();
		if (this.pendingSpace) {
			this.addInsertEdit(currentTokenStartPosition, " "); //$NON-NLS-1$
		}
		this.needSpace = false;		
		this.pendingSpace = false;		
		int previousStart = currentTokenStartPosition;

		loop: while (nextCharacterStart <= currentTokenEndPosition && (currentCharacter = this.scanner.getNextChar()) != -1) {
			nextCharacterStart = this.scanner.currentPosition;

			switch(currentCharacter) {
				case '\r' :
					start = previousStart;
					break loop;
				case '\n' :
					start = previousStart;
					break loop;
			}
			previousStart = nextCharacterStart;
		}
		if (start != currentTokenStartPosition) {
			addReplaceEdit(start, currentTokenEndPosition - 1, lineSeparator);
		}
		line++; 
		column = 1;
		needSpace = false;
		this.pendingSpace = false;
		lastNumberOfNewLines = 1;
		// realign to the proper value
		if (this.currentAlignment != null) {
			if (this.memberAlignment != null) {
				// select the last alignment
				if (this.currentAlignment.location.inputOffset > this.memberAlignment.location.inputOffset) {
					this.indentationLevel = Math.max(this.indentationLevel, this.currentAlignment.breakIndentationLevel);
				} else {
					this.indentationLevel = Math.max(this.indentationLevel, this.memberAlignment.breakIndentationLevel);
				}
			} else {
				this.indentationLevel = Math.max(this.indentationLevel, this.currentAlignment.breakIndentationLevel);
			}
		}
		this.scanner.resetTo(currentTokenEndPosition, this.scannerEndPosition - 1);
	}
	public void printEmptyLines(int linesNumber) {
		this.printEmptyLines(linesNumber, this.scanner.getCurrentTokenEndPosition() + 1);
	}

	private void printEmptyLines(int linesNumber, int insertPosition) {
		if (this.nlsTagCounter > 0) {
			return;
		}
		StringBuffer buffer = new StringBuffer();
		if (lastNumberOfNewLines == 0) {
			linesNumber++; // add an extra line breaks
			for (int i = 0; i < linesNumber; i++) {
				buffer.append(this.lineSeparator);
			}
			lastNumberOfNewLines += linesNumber;
			line += linesNumber;
			column = 1;
			needSpace = false;
			this.pendingSpace = false;
		} else if (lastNumberOfNewLines == 1) {
			for (int i = 0; i < linesNumber; i++) {
				buffer.append(this.lineSeparator);
			}
			lastNumberOfNewLines += linesNumber;
			line += linesNumber;
			column = 1;
			needSpace = false;
			this.pendingSpace = false;
		} else {
			if ((lastNumberOfNewLines - 1) >= linesNumber) {
				// there is no need to add new lines
				return;
			}
			final int realNewLineNumber = linesNumber - lastNumberOfNewLines + 1;
			for (int i = 0; i < realNewLineNumber; i++) {
				buffer.append(this.lineSeparator);
			}
			lastNumberOfNewLines += realNewLineNumber;
			line += realNewLineNumber;
			column = 1;
			needSpace = false;
			this.pendingSpace = false;
		}
		addInsertEdit(insertPosition, buffer.toString());
	}

	private void printIndentationIfNecessary() {
		StringBuffer buffer = new StringBuffer();
		printIndentationIfNecessary(buffer);
		if (buffer.length() > 0) {
			addInsertEdit(this.scanner.getCurrentTokenStartPosition(), buffer.toString());
			this.pendingSpace = false;
		}
	}

	private void printIndentationIfNecessary(StringBuffer buffer) {
		switch(this.tabChar) {
			case DefaultCodeFormatterOptions.TAB :
				boolean useTabsForLeadingIndents = this.useTabsOnlyForLeadingIndents;
				int numberOfLeadingIndents = this.numberOfIndentations;
				int indentationsAsTab = 0;
				if (useTabsForLeadingIndents) {
					while (this.column <= this.indentationLevel) {
						if (indentationsAsTab < numberOfLeadingIndents) {
							buffer.append('\t');
							indentationsAsTab++;
							this.lastNumberOfNewLines = 0;
							int complement = this.tabLength - ((this.column - 1) % this.tabLength); // amount of space
							this.column += complement;
							this.needSpace = false;
						} else {
							buffer.append(' ');
							this.column++;
							this.needSpace = false;
						}
					}
				} else {
					while (this.column <= this.indentationLevel) {
						buffer.append('\t');
						this.lastNumberOfNewLines = 0;
						int complement = this.tabLength - ((this.column - 1) % this.tabLength); // amount of space
						this.column += complement;
						this.needSpace = false;
					}
				}
				break;
			case DefaultCodeFormatterOptions.SPACE :
				while (this.column <= this.indentationLevel) {
					buffer.append(' ');
					this.column++;
					this.needSpace = false;
				}
				break;
			case DefaultCodeFormatterOptions.MIXED :
				useTabsForLeadingIndents = this.useTabsOnlyForLeadingIndents;
				numberOfLeadingIndents = this.numberOfIndentations;
				indentationsAsTab = 0;
				if (useTabsForLeadingIndents) {
					final int columnForLeadingIndents = numberOfLeadingIndents * this.indentationSize;
					while (this.column <= this.indentationLevel) {
						if (this.column <= columnForLeadingIndents) {
							if ((this.column - 1 + this.tabLength) <= this.indentationLevel) {
								buffer.append('\t');
								this.column += this.tabLength;
							} else if ((this.column - 1 + this.indentationSize) <= this.indentationLevel) {
								// print one indentation
								for (int i = 0, max = this.indentationSize; i < max; i++) {
									buffer.append(' ');
									this.column++;
								}
							} else {
								buffer.append(' ');
								this.column++;
							}
						} else {
							for (int i = this.column, max = this.indentationLevel; i <= max; i++) {
								buffer.append(' ');
								this.column++;
							}
						}
						this.needSpace = false;
					}
				} else {
					while (this.column <= this.indentationLevel) {
						if ((this.column - 1 + this.tabLength) <= this.indentationLevel) {
							buffer.append('\t');
							this.column += this.tabLength;
						} else if ((this.column - 1 + this.indentationSize) <= this.indentationLevel) {
							// print one indentation
							for (int i = 0, max = this.indentationSize; i < max; i++) {
								buffer.append(' ');
								this.column++;
							}
						} else {
							buffer.append(' ');
							this.column++;
						}
						this.needSpace = false;
					}
				}
				break;
		}
	}

	public void printModifiers(Annotation[] annotations, ASTVisitor visitor) {
		try {
			int annotationsLength = annotations != null ? annotations.length : 0;
			int annotationsIndex = 0;
			boolean isFirstModifier = true;
			int currentTokenStartPosition = this.scanner.currentPosition;
			boolean hasComment = false;
			boolean hasModifiers = false;
			while ((this.currentToken = this.scanner.getNextToken()) != TerminalTokens.TokenNameEOF) {
				switch(this.currentToken) {
					case TerminalTokens.TokenNamepublic :
					case TerminalTokens.TokenNameprotected :
					case TerminalTokens.TokenNameprivate :
					case TerminalTokens.TokenNamestatic :
					case TerminalTokens.TokenNameabstract :
					case TerminalTokens.TokenNamefinal :
					case TerminalTokens.TokenNamenative :
					case TerminalTokens.TokenNamesynchronized :
					case TerminalTokens.TokenNametransient :
					case TerminalTokens.TokenNamevolatile :
					case TerminalTokens.TokenNamestrictfp :
						hasModifiers = true;
						this.print(this.scanner.getRawTokenSource(), !isFirstModifier);
						isFirstModifier = false;
						currentTokenStartPosition = this.scanner.getCurrentTokenStartPosition();
						break;
					case TerminalTokens.TokenNameAT :
						hasModifiers = true;
						if (!isFirstModifier) {
							this.space();
						}
						this.scanner.resetTo(this.scanner.getCurrentTokenStartPosition(), this.scannerEndPosition - 1);
						if (annotationsIndex < annotationsLength) {
							annotations[annotationsIndex++].traverse(visitor, (BlockScope) null);
							if (this.formatter.preferences.insert_new_line_after_annotation) {
								this.printNewLine();
							}
						} else {
							return;
						}
						isFirstModifier = false;
						currentTokenStartPosition = this.scanner.currentPosition;
						break;
					case TerminalTokens.TokenNameCOMMENT_BLOCK :
						this.printBlockComment(this.scanner.getRawTokenSource(), false);
						currentTokenStartPosition = this.scanner.currentPosition;
						hasComment = true;
						break;
					case TerminalTokens.TokenNameCOMMENT_JAVADOC :
						this.printBlockComment(this.scanner.getRawTokenSource(), true);
						currentTokenStartPosition = this.scanner.currentPosition;
						hasComment = true;
						break;
					case TerminalTokens.TokenNameCOMMENT_LINE :
						this.printCommentLine(this.scanner.getRawTokenSource());
						currentTokenStartPosition = this.scanner.currentPosition;
						break;
					case TerminalTokens.TokenNameWHITESPACE :
						addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
						int count = 0;
						char[] whiteSpaces = this.scanner.getCurrentTokenSource();
						for (int i = 0, max = whiteSpaces.length; i < max; i++) {
							switch(whiteSpaces[i]) {
								case '\r' :
									if ((i + 1) < max) {
										if (whiteSpaces[i + 1] == '\n') {
											i++;
										}
									}
									count++;
									break;
								case '\n' :
									count++;
							}
						}
						if (count >= 1 && hasComment) {
							printNewLine();
						}
						currentTokenStartPosition = this.scanner.currentPosition;
						hasComment = false;
						break;
					default:
						if (hasModifiers) {
							this.space();
						}
						// step back one token
						this.scanner.resetTo(currentTokenStartPosition, this.scannerEndPosition - 1);
						return;					
				}
			}
		} catch (InvalidInputException e) {
			throw new AbortFormatting(e);
		}
	}
	
	public void printNewLine() {
		if (this.nlsTagCounter > 0) {
			return;
		}
		if (lastNumberOfNewLines >= 1) {
			column = 1; // ensure that the scribe is at the beginning of a new line
			return;
		}
		addInsertEdit(this.scanner.getCurrentTokenEndPosition() + 1, this.lineSeparator);
		line++;
		lastNumberOfNewLines = 1;
		column = 1;
		needSpace = false;
		this.pendingSpace = false;
	}

	public void printNewLine(int insertPosition) {
		if (this.nlsTagCounter > 0) {
			return;
		}
		if (lastNumberOfNewLines >= 1) {
			column = 1; // ensure that the scribe is at the beginning of a new line
			return;
		}
		addInsertEdit(insertPosition, this.lineSeparator);
		line++;
		lastNumberOfNewLines = 1;
		column = 1;
		needSpace = false;
		this.pendingSpace = false;
	}

	public void printNextToken(int expectedTokenType){
		printNextToken(expectedTokenType, false);
	}

	public void printNextToken(int expectedTokenType, boolean considerSpaceIfAny){
		printComment();
		try {
			this.currentToken = this.scanner.getNextToken();
			char[] currentTokenSource = this.scanner.getRawTokenSource();
			if (expectedTokenType != this.currentToken) {
				throw new AbortFormatting("unexpected token type, expecting:"+expectedTokenType+", actual:"+this.currentToken);//$NON-NLS-1$//$NON-NLS-2$
			}
			this.print(currentTokenSource, considerSpaceIfAny);
		} catch (InvalidInputException e) {
			throw new AbortFormatting(e);
		}
	}

	public void printNextToken(int[] expectedTokenTypes) {
		printNextToken(expectedTokenTypes, false);
	}

	public void printNextToken(int[] expectedTokenTypes, boolean considerSpaceIfAny){
		printComment();
		try {
			this.currentToken = this.scanner.getNextToken();
			char[] currentTokenSource = this.scanner.getRawTokenSource();
			if (Arrays.binarySearch(expectedTokenTypes, this.currentToken) < 0) {
				StringBuffer expectations = new StringBuffer(5);
				for (int i = 0; i < expectedTokenTypes.length; i++){
					if (i > 0) {
						expectations.append(',');
					}
					expectations.append(expectedTokenTypes[i]);
				}				
				throw new AbortFormatting("unexpected token type, expecting:["+expectations.toString()+"], actual:"+this.currentToken);//$NON-NLS-1$//$NON-NLS-2$
			}
			this.print(currentTokenSource, considerSpaceIfAny);
		} catch (InvalidInputException e) {
			throw new AbortFormatting(e);
		}
	}

	public void printQualifiedReference(int sourceEnd) {
		int currentTokenStartPosition = this.scanner.currentPosition;
		try {
			do {
				this.printComment();
				switch(this.currentToken = this.scanner.getNextToken()) {
					case TerminalTokens.TokenNameEOF :
						return;
					case TerminalTokens.TokenNameWHITESPACE :
						addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
						currentTokenStartPosition = this.scanner.currentPosition;
						break;
					case TerminalTokens.TokenNameCOMMENT_BLOCK :
					case TerminalTokens.TokenNameCOMMENT_JAVADOC :
						this.printBlockComment(this.scanner.getRawTokenSource(), false);
						currentTokenStartPosition = this.scanner.currentPosition;
						break;
					case TerminalTokens.TokenNameCOMMENT_LINE :
						this.printCommentLine(this.scanner.getRawTokenSource());
						currentTokenStartPosition = this.scanner.currentPosition;
						break;
					case TerminalTokens.TokenNameIdentifier :
					case TerminalTokens.TokenNameDOT :
						this.print(this.scanner.getRawTokenSource(), false);
						currentTokenStartPosition = this.scanner.currentPosition;
						break;
					default:
						this.scanner.resetTo(currentTokenStartPosition, this.scannerEndPosition - 1);
						return;
				}
			} while (this.scanner.currentPosition <= sourceEnd);
		} catch(InvalidInputException e) {
			throw new AbortFormatting(e);
		}
	}

	private void printRule(StringBuffer stringBuffer) {
		for (int i = 0; i < this.pageWidth; i++){
			if ((i % this.tabLength) == 0) { 
				stringBuffer.append('+');
			} else {
				stringBuffer.append('-');
			}
		}
		stringBuffer.append(this.lineSeparator);
		
		for (int i = 0; i < (pageWidth / tabLength); i++) {
			stringBuffer.append(i);
			stringBuffer.append('\t');
		}			
	}

	public void printTrailingComment() {
		try {
			// if we have a space between two tokens we ensure it will be dumped in the formatted string
			int currentTokenStartPosition = this.scanner.currentPosition;
			boolean hasWhitespaces = false;
			boolean hasComment = false;
			boolean hasLineComment = false;
			while ((this.currentToken = this.scanner.getNextToken()) != TerminalTokens.TokenNameEOF) {
				switch(this.currentToken) {
					case TerminalTokens.TokenNameWHITESPACE :
						int count = 0;
						char[] whiteSpaces = this.scanner.getCurrentTokenSource();
						for (int i = 0, max = whiteSpaces.length; i < max; i++) {
							switch(whiteSpaces[i]) {
								case '\r' :
									if ((i + 1) < max) {
										if (whiteSpaces[i + 1] == '\n') {
											i++;
										}
									}
									count++;
									break;
								case '\n' :
									count++;
							}
						}
						if (hasLineComment) {
							if (count >= 1) {
								currentTokenStartPosition = this.scanner.getCurrentTokenStartPosition();
								this.preserveEmptyLines(count, currentTokenStartPosition);
								addDeleteEdit(currentTokenStartPosition, this.scanner.getCurrentTokenEndPosition());
								this.scanner.resetTo(this.scanner.currentPosition, this.scannerEndPosition - 1);
								return;
							} else {
								this.scanner.resetTo(currentTokenStartPosition, this.scannerEndPosition - 1);
								return;
							}
						} else if (count >= 1) {
							if (hasComment) {
								this.printNewLine(this.scanner.getCurrentTokenStartPosition());
							}
							this.scanner.resetTo(currentTokenStartPosition, this.scannerEndPosition - 1);
							return;
						} else {
							hasWhitespaces = true;
							currentTokenStartPosition = this.scanner.currentPosition;						
							addDeleteEdit(this.scanner.getCurrentTokenStartPosition(), this.scanner.getCurrentTokenEndPosition());
						}
						break;
					case TerminalTokens.TokenNameCOMMENT_LINE :
						if (hasWhitespaces) {
							space();
						}
						this.printCommentLine(this.scanner.getRawTokenSource());
						currentTokenStartPosition = this.scanner.currentPosition;
						hasLineComment = true;
						break;
					case TerminalTokens.TokenNameCOMMENT_BLOCK :
						if (hasWhitespaces) {
							space();
						}
						this.printBlockComment(this.scanner.getRawTokenSource(), false);
						currentTokenStartPosition = this.scanner.currentPosition;
						hasComment = true;
						break;
					default :
						// step back one token
						this.scanner.resetTo(currentTokenStartPosition, this.scannerEndPosition - 1);
						return;
				}
			}
		} catch (InvalidInputException e) {
			throw new AbortFormatting(e);
		}
	}

	void redoAlignment(AlignmentException e){
		if (e.relativeDepth > 0) { // if exception targets a distinct context
			e.relativeDepth--; // record fact that current context got traversed
			this.currentAlignment = this.currentAlignment.enclosing; // pop currentLocation
			throw e; // rethrow
		} 
		// reset scribe/scanner to restart at this given location
		this.resetAt(this.currentAlignment.location);
		this.scanner.resetTo(this.currentAlignment.location.inputOffset, this.scanner.eofPosition);
		// clean alignment chunkKind so it will think it is a new chunk again
		this.currentAlignment.chunkKind = 0;
	}

	void redoMemberAlignment(AlignmentException e){
		// reset scribe/scanner to restart at this given location
		this.resetAt(this.memberAlignment.location);
		this.scanner.resetTo(this.memberAlignment.location.inputOffset, this.scanner.eofPosition);
		// clean alignment chunkKind so it will think it is a new chunk again
		this.memberAlignment.chunkKind = 0;
	}

	public void reset() {
		this.checkLineWrapping = true;
		this.line = 0;
		this.column = 1;
		this.editsIndex = 0;
		this.nlsTagCounter = 0;
	}
		
	private void resetAt(Location location) {
		this.line = location.outputLine;
		this.column = location.outputColumn;
		this.indentationLevel = location.outputIndentationLevel;
		this.numberOfIndentations = location.numberOfIndentations;
		this.lastNumberOfNewLines = location.lastNumberOfNewLines;
		this.needSpace = location.needSpace;
		this.pendingSpace = location.pendingSpace;
		this.editsIndex = location.editsIndex;
		this.nlsTagCounter = location.nlsTagCounter;
		if (this.editsIndex > 0) {
			this.edits[this.editsIndex - 1] = location.textEdit;
		}
		this.formatter.lastLocalDeclarationSourceStart = location.lastLocalDeclarationSourceStart;
	}

	private void resize() {
		System.arraycopy(this.edits, 0, (this.edits = new OptimizedReplaceEdit[this.editsIndex * 2]), 0, this.editsIndex);
	}

	public void space() {
		if (!this.needSpace) return;
		this.lastNumberOfNewLines = 0;
		this.pendingSpace = true;
		this.column++;
		this.needSpace = false;		
	}

	public String toString() {
		StringBuffer stringBuffer = new StringBuffer();
		stringBuffer
			.append("(page width = " + this.pageWidth + ") - (tabChar = ");//$NON-NLS-1$//$NON-NLS-2$
		switch(this.tabChar) {
			case DefaultCodeFormatterOptions.TAB :
				 stringBuffer.append("TAB");//$NON-NLS-1$
				 break;
			case DefaultCodeFormatterOptions.SPACE :
				 stringBuffer.append("SPACE");//$NON-NLS-1$
				 break;
			default :
				 stringBuffer.append("MIXED");//$NON-NLS-1$
		}
		stringBuffer
			.append(") - (tabSize = " + this.tabLength + ")")//$NON-NLS-1$//$NON-NLS-2$
			.append(this.lineSeparator)
			.append("(line = " + this.line + ") - (column = " + this.column + ") - (identationLevel = " + this.indentationLevel + ")")	//$NON-NLS-1$	//$NON-NLS-2$	//$NON-NLS-3$	//$NON-NLS-4$
			.append(this.lineSeparator)
			.append("(needSpace = " + this.needSpace + ") - (lastNumberOfNewLines = " + this.lastNumberOfNewLines + ") - (checkLineWrapping = " + this.checkLineWrapping + ")")	//$NON-NLS-1$	//$NON-NLS-2$	//$NON-NLS-3$	//$NON-NLS-4$
			.append(this.lineSeparator)
			.append("==================================================================================")	//$NON-NLS-1$
			.append(this.lineSeparator);
		printRule(stringBuffer);
		return stringBuffer.toString();
	}
	
	public void unIndent() {
		this.indentationLevel -= this.indentationSize;
		this.numberOfIndentations--;
	}
}