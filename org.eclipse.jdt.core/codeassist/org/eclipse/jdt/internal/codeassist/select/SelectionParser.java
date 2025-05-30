/*******************************************************************************
 * Copyright (c) 2000, 2023 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Jesper Steen Møller <jesper@selskabet.org> - contributions for:
 *         Bug 531046: [10] ICodeAssist#codeSelect support for 'var'
 *******************************************************************************/
package org.eclipse.jdt.internal.codeassist.select;

import static org.eclipse.jdt.internal.compiler.parser.TerminalToken.TokenNameCaseArrow;
import static org.eclipse.jdt.internal.compiler.parser.TerminalToken.TokenNameIdentifier;
import static org.eclipse.jdt.internal.compiler.parser.TerminalToken.TokenNameInvalid;
import static org.eclipse.jdt.internal.compiler.parser.TerminalToken.TokenNameLBRACE;
import static org.eclipse.jdt.internal.compiler.parser.TerminalToken.TokenNameNotAToken;
import static org.eclipse.jdt.internal.compiler.parser.TerminalToken.TokenNameRBRACE;
import static org.eclipse.jdt.internal.compiler.parser.TerminalToken.TokenNameSEMICOLON;
import static org.eclipse.jdt.internal.compiler.parser.TerminalToken.TokenNameif;
import static org.eclipse.jdt.internal.compiler.parser.TerminalToken.TokenNamewhile;

/*
 * Parser able to build specific completion parse nodes, given a cursorLocation.
 *
 * Cursor location denotes the position of the last character behind which completion
 * got requested:
 *  -1 means completion at the very beginning of the source
 *	0  means completion behind the first character
 *  n  means completion behind the n-th character
 */

import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.codeassist.impl.AssistParser;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.ast.*;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.parser.JavadocParser;
import org.eclipse.jdt.internal.compiler.parser.RecoveredType;
import org.eclipse.jdt.internal.compiler.parser.TerminalToken;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.eclipse.jdt.internal.compiler.util.Util;

public class SelectionParser extends AssistParser {
	// OWNER
	protected static final int SELECTION_PARSER = 1024;
	protected static final int SELECTION_OR_ASSIST_PARSER = ASSIST_PARSER + SELECTION_PARSER;

	// KIND : all values known by SelectionParser are between 1025 and 1549
	protected static final int K_BETWEEN_CASE_AND_COLONORARROW = SELECTION_PARSER + 1; // whether we are inside a block
	protected static final int K_INSIDE_RETURN_STATEMENT = SELECTION_PARSER + 2; // whether we are between the keyword 'return' and the end of a return statement
	protected static final int K_CAST_STATEMENT = SELECTION_PARSER + 3; // whether we are between ')' and the end of a cast statement

	protected static final int K_POST_AND_AND = SELECTION_PARSER + 4; // whether we are in: expression && <here>
	protected static final int K_POST_OR_OR = SELECTION_PARSER + 5;   // whether we are in: expression || <here>

	protected static final int K_INSIDE_IF = SELECTION_PARSER + 6; // whether we are in an if statement
	protected static final int K_INSIDE_THEN = SELECTION_PARSER + 7; // whether we are in the then portion of an if statement
	protected static final int K_INSIDE_ELSE = SELECTION_PARSER + 8; // whether we are in the else portion of an if statement

	protected static final int K_INSIDE_WHILE = SELECTION_PARSER + 9; // whether we are in a while statement
	protected static final int K_POST_WHILE_EXPRESSION = SELECTION_PARSER + 10; // whether we are in an while statement's body

	protected static final int K_INSIDE_STATEMENT_SWITCH = SELECTION_PARSER + 11; // whether we are in a switch statement
	protected static final int K_INSIDE_EXPRESSION_SWITCH = SELECTION_PARSER + 12; // whether we are in an expression switch
	protected static final int K_INSIDE_WHEN = SELECTION_PARSER + 13; // whether we are in the guard

	protected static final int K_INSIDE_FOR_EACH = SELECTION_PARSER + 14; // whether we are in a for each statement

	protected static final int K_INSIDE_INITIALIZATION = SELECTION_PARSER + 15; // whether we are in an initialization of a field/local declaration
	protected static final int K_INSIDE_ASSIGNMENT = SELECTION_PARSER + 16; // whether we are in an assignment statement


	/* https://bugs.eclipse.org/bugs/show_bug.cgi?id=476693
	 * https://bugs.eclipse.org/bugs/show_bug.cgi?id=515758
	 * Records whether and when we found an invocation being selected:
	 * 0: not found
	 * 1: found just now
	 * 2...: inside block (lambda body) within the invocation found before
	 * Rationale: we really need to complete parsing the invocation for resolving to succeed.
	 */
	private int selectionNodeFoundLevel = 0;
	private boolean parsingEmbeddedExpression = false;
	public ASTNode assistNodeParent; // the parent node of assist node

	/* public fields */

	public int selectionStart, selectionEnd;

	public static final char[] SUPER = "super".toCharArray(); //$NON-NLS-1$
	public static final char[] THIS = "this".toCharArray(); //$NON-NLS-1$

public SelectionParser(ProblemReporter problemReporter) {
	super(problemReporter);
	this.javadocParser.checkDocComment = true;
}
@Override
public char[] assistIdentifier(){
	return ((SelectionScanner)this.scanner).selectionIdentifier;
}
protected void attachOrphanCompletionNode(){
	if (this.isOrphanCompletionNode){
		ASTNode orphan = this.assistNode;
		this.isOrphanCompletionNode = false;

		/* if in context of a type, then persists the identifier into a fake field return type */
		if (this.currentElement instanceof RecoveredType){
			RecoveredType recoveredType = (RecoveredType)this.currentElement;
			/* filter out cases where scanner is still inside type header */
			if (recoveredType.foundOpeningBrace) {
				/* generate a pseudo field with a completion on type reference */
				if (orphan instanceof TypeReference){
					this.currentElement = this.currentElement.add(new SelectionOnFieldType((TypeReference)orphan), 0);
					return;
				}
			}
		}

		if (orphan instanceof Expression && ((Expression) orphan).isTrulyExpression()) {
			buildMoreCompletionContext((Expression)orphan);
		} else {
			if (lastIndexOfElement(K_LAMBDA_EXPRESSION_DELIMITER) < 0) { // lambdas are recovered up to the containing expression statement and will carry along the assist node anyways.
				Statement statement = (Statement) orphan;
				this.currentElement = this.currentElement.add(statement, 0);
			}
		}
		if (isIndirectlyInsideLambdaExpression()) {
			if (this.currentToken == TokenNameLBRACE)
				this.ignoreNextOpeningBrace = true;
			else if (this.currentToken == TokenNameRBRACE)
				this.ignoreNextClosingBrace = true;
		} else {
			this.currentToken = TokenNameNotAToken; // given we are not on an eof, we do not want side effects caused by looked-ahead token
		}
	}
}
private void buildMoreCompletionContext(Expression expression) {

	Statement parentNode = null;
	Statement orphan = expression;

	Statement thenStat = null;
	Statement elseStat = null;

	Expression right = expression;
	Expression left = null;

	Statement whileBody = null;

	int kind;

	for (int i = this.elementPtr; i > -1; i--) {
		switch (kind = this.elementKindStack[i]) {
			case K_INSIDE_WHILE:
				if (whileBody == null) // selection inside while (<here>)
					whileBody = new EmptyStatement(orphan.sourceEnd,  orphan.sourceEnd);
				// else whileBody is what was obtained in the bottom up context building
				parentNode = orphan = new WhileStatement((Expression) orphan, whileBody, 0, 0);
				whileBody = null;
				break;
			case K_INSIDE_IF:
				if (thenStat == null) // selection inside if (<here>)
					thenStat = new EmptyStatement(orphan.sourceEnd,  orphan.sourceEnd);
				// else thenStat and elseStat are what they are obtained in the bottom up context building
				parentNode = orphan = new IfStatement((Expression) orphan, thenStat, elseStat, 0, 0);
				thenStat = elseStat = null;
				break;
			case K_BETWEEN_CASE_AND_COLONORARROW:
				parentNode = orphan = new CaseStatement( new Expression [] { (Expression) orphan }, orphan.sourceStart, orphan.sourceEnd);
				break;
			case K_INSIDE_WHEN:
				if (this.astPtr >=0 && this.astStack[this.astPtr] instanceof Pattern && orphan instanceof Expression) {
					this.astLengthPtr--;
					Pattern pattern = (Pattern) this.astStack[this.astPtr--];
					parentNode = orphan = new GuardedPattern(pattern, (Expression) orphan);
				}
				break;
			case K_POST_WHILE_EXPRESSION:
			case K_INSIDE_THEN:
			case K_INSIDE_ELSE:
			case K_INSIDE_STATEMENT_SWITCH:
			case K_INSIDE_EXPRESSION_SWITCH:
			case K_INSIDE_FOR_EACH:
				int newAstPtr = (int) this.elementObjectInfoStack[i];
				int length = this.astPtr - newAstPtr;
				Statement[] statements = new Statement[length + (orphan != null ? 1 : 0)];
				System.arraycopy(
					this.astStack,
					newAstPtr + 1,
					statements,
					0,
					length);
				if (orphan != null)
					statements[length] = orphan;
				this.astPtr = newAstPtr;
				Block b = new Block(0);
				b.statements = statements;
				switch (kind) {
					case K_INSIDE_THEN:
						thenStat = b;
						this.expressionPtr = this.elementInfoStack[i];
						orphan = this.expressionStack[this.expressionPtr--];
						break;
					case K_INSIDE_ELSE:
						elseStat = b;
						orphan = null; // orphan subsumed into the dangling else
						break;
					case K_INSIDE_FOR_EACH:
						ForeachStatement forEach = (ForeachStatement)this.astStack[this.astPtr--];
						forEach.action = b;
						orphan = forEach;
						break;
					case K_POST_WHILE_EXPRESSION:
						whileBody = b;
						this.expressionPtr = this.elementInfoStack[i];
						orphan = this.expressionStack[this.expressionPtr--];
						break;
					case K_INSIDE_STATEMENT_SWITCH:
					case K_INSIDE_EXPRESSION_SWITCH:
						boolean exprSwitch = kind == K_INSIDE_EXPRESSION_SWITCH;
						SwitchStatement switchStatement = exprSwitch ? new SwitchExpression() : new SwitchStatement();
						this.expressionPtr = this.elementInfoStack[i];
						switchStatement.expression = this.expressionStack[this.expressionPtr--];
						switchStatement.statements = statements;
						parentNode = orphan = switchStatement;
						break;
				}
				break;
			case K_INSIDE_RETURN_STATEMENT :
				int elementInfo = this.elementInfoStack[i];
				if (elementInfo == this.bracketDepth) {
					ReturnStatement returnStatement = new ReturnStatement(expression, expression.sourceStart, expression.sourceEnd);
					parentNode = returnStatement;
					this.assistNodeParent = parentNode;
					orphan = returnStatement;
				}
				break;
			case K_POST_AND_AND:
				left = this.expressionStack[this.elementInfoStack[i]];
				right = new AND_AND_Expression(
						left,
						right,
						AND_AND);
				parentNode = orphan = right;
				break;
			case K_POST_OR_OR:
				left = this.expressionStack[this.elementInfoStack[i]];
				right = new OR_OR_Expression(
						left,
						right,
						OR_OR);
				parentNode = orphan = right;
				break;
			case K_CAST_STATEMENT :
				Expression castType;
				if (this.expressionPtr > 0
					&& ((castType = this.expressionStack[this.expressionPtr-1]) instanceof TypeReference)) {
					CastExpression cast = new CastExpression(expression, (TypeReference) castType);
					cast.sourceStart = castType.sourceStart;
					cast.sourceEnd= expression.sourceEnd;
					parentNode = orphan = cast;
					this.assistNodeParent = parentNode;
				}
				break;
			case K_INSIDE_ASSIGNMENT:
				if (orphan instanceof SwitchExpression) { // ensure switch expression is not mistakenly seen in vanilla context
					this.expressionLengthPtr--;
					parentNode = orphan =
							new Assignment(
								this.expressionStack[this.expressionPtr--],
								(Expression) orphan,
								orphan.sourceEnd);
					}
				break;
			case K_INSIDE_INITIALIZATION:
				if (orphan instanceof SwitchExpression) { // ensure switch expression is not mistakenly seen in vanilla context
					AbstractVariableDeclaration variable = (AbstractVariableDeclaration) this.astStack[this.astPtr--];
					variable.initialization = (Expression) orphan;
				}
				break;
		}
	}

	// Do not add assist node/parent into the recovery system if we are inside a lambda. The lambda will be fully recovered including the containing statement and added.
	if (lastIndexOfElement(K_LAMBDA_EXPRESSION_DELIMITER) < 0) {
		if(parentNode != null) {
			this.currentElement = this.currentElement.add(parentNode, 0);
		} else {
			this.currentElement = this.currentElement.add((Statement)wrapWithExplicitConstructorCallIfNeeded(expression), 0);
			if(this.lastCheckPoint < expression.sourceEnd) {
				this.lastCheckPoint = expression.sourceEnd + 1;
			}
		}
	}
}
private boolean checkRecoveredType() {
	if (this.currentElement instanceof RecoveredType){
		/* check if current awaiting identifier is the completion identifier */
		if (this.indexOfAssistIdentifier() < 0) return false;

		if ((this.lastErrorEndPosition >= this.selectionStart)
			&& (this.lastErrorEndPosition <= this.selectionEnd+1)){
			return false;
		}
		RecoveredType recoveredType = (RecoveredType)this.currentElement;
		/* filter out cases where scanner is still inside type header */
		if (recoveredType.foundOpeningBrace) {
			this.assistNode = this.getTypeReference(0);
			this.lastCheckPoint = this.assistNode.sourceEnd + 1;
			this.isOrphanCompletionNode = true;
			return true;
		}
	}
	return false;
}
@Override
protected void classInstanceCreation(boolean hasClassBody) {

	// ClassInstanceCreationExpression ::= 'new' ClassType '(' ArgumentListopt ')' ClassBodyopt

	// ClassBodyopt produces a null item on the astStak if it produces NO class body
	// An empty class body produces a 0 on the length stack.....


	if ((this.astLengthStack[this.astLengthPtr] == 1)
		&& (this.astStack[this.astPtr] == null)) {


		int index;
		if ((index = this.indexOfAssistIdentifier()) < 0) {
			super.classInstanceCreation(hasClassBody);
			return;
		} else if(this.identifierLengthPtr > -1 &&
					(this.identifierLengthStack[this.identifierLengthPtr] - 1) != index) {
			super.classInstanceCreation(hasClassBody);
			return;
		}
		QualifiedAllocationExpression alloc;
		this.astPtr--;
		this.astLengthPtr--;
		alloc = new SelectionOnQualifiedAllocationExpression();
		alloc.sourceEnd = this.endPosition; //the position has been stored explicitly

		int length;
		if ((length = this.expressionLengthStack[this.expressionLengthPtr--]) != 0) {
			this.expressionPtr -= length;
			System.arraycopy(
				this.expressionStack,
				this.expressionPtr + 1,
				alloc.arguments = new Expression[length],
				0,
				length);
		}
		// trick to avoid creating a selection on type reference
		char [] oldIdent = assistIdentifier();
		setAssistIdentifier(null);
		alloc.type = getTypeReference(0);
		checkForDiamond(alloc.type);

		setAssistIdentifier(oldIdent);

		//the default constructor with the correct number of argument
		//will be created and added by the TC (see createsInternalConstructorWithBinding)
		alloc.sourceStart = this.intStack[this.intPtr--];
		pushOnExpressionStack(alloc);

		this.assistNode = alloc;
		this.lastCheckPoint = alloc.sourceEnd + 1;
		if (!this.diet){
			this.restartRecovery	= true;	// force to restart in recovery mode
			this.lastIgnoredToken = TokenNameInvalid;
		}
		this.isOrphanCompletionNode = true;
	} else {
		super.classInstanceCreation(hasClassBody);
		if (this.assistNode != null) {
			QualifiedAllocationExpression alloc = (QualifiedAllocationExpression) this.expressionStack[this.expressionPtr];
			if (this.assistNode.sourceStart >= alloc.sourceStart && this.assistNode.sourceEnd <= alloc.sourceEnd)
				this.selectionNodeFoundLevel = 1; // continue to parse until containing block statement is reduced, so as to
			    // ensure the type declaration makes it to the astStack (possibly as a subexpression or as an expression statement)
		}
	}
}
@Override
protected void consumeArrayCreationExpressionWithoutInitializer() {
	// ArrayCreationWithoutArrayInitializer ::= 'new' PrimitiveType DimWithOrWithOutExprs
	// ArrayCreationWithoutArrayInitializer ::= 'new' ClassOrInterfaceType DimWithOrWithOutExprs

	super.consumeArrayCreationExpressionWithoutInitializer();

	ArrayAllocationExpression alloc = (ArrayAllocationExpression)this.expressionStack[this.expressionPtr];
	if (alloc.type == this.assistNode){
		if (!this.diet){
			this.restartRecovery	= true;	// force to restart in recovery mode
			this.lastIgnoredToken = TokenNameInvalid;
		}
		this.isOrphanCompletionNode = true;
	}
}
@Override
protected void consumeArrayCreationExpressionWithInitializer() {
	// ArrayCreationWithArrayInitializer ::= 'new' ClassOrInterfaceType DimWithOrWithOutExprs ArrayInitializer

	super.consumeArrayCreationExpressionWithInitializer();

	ArrayAllocationExpression alloc = (ArrayAllocationExpression)this.expressionStack[this.expressionPtr];
	if (alloc.type == this.assistNode){
		if (!this.diet){
			this.restartRecovery	= true;	// force to restart in recovery mode
			this.lastIgnoredToken = TokenNameInvalid;
		}
		this.isOrphanCompletionNode = true;
	}
}
@Override
protected void consumeCastExpressionLL1() {
	popElement(K_CAST_STATEMENT);
	super.consumeCastExpressionLL1();
}
@Override
protected void consumeCastExpressionLL1WithBounds() {
	popElement(K_CAST_STATEMENT);
	super.consumeCastExpressionLL1WithBounds();
}
@Override
protected void consumeCastExpressionWithGenericsArray() {
	popElement(K_CAST_STATEMENT);
	super.consumeCastExpressionWithGenericsArray();
}
@Override
protected void consumeCastExpressionWithNameArray() {
	popElement(K_CAST_STATEMENT);
	super.consumeCastExpressionWithNameArray();
}
@Override
protected void consumeCastExpressionWithPrimitiveType() {
	popElement(K_CAST_STATEMENT);
	super.consumeCastExpressionWithPrimitiveType();
}
@Override
protected void consumeCastExpressionWithQualifiedGenericsArray() {
	popElement(K_CAST_STATEMENT);
	super.consumeCastExpressionWithQualifiedGenericsArray();
}
@Override
protected void consumeCatchFormalParameter() {
	if (this.indexOfAssistIdentifier() < 0) {
		super.consumeCatchFormalParameter();
		if((!this.diet || this.dietInt != 0) && this.astPtr > -1) {
			Argument argument = (Argument) this.astStack[this.astPtr];
			if(argument.type == this.assistNode) {
				this.isOrphanCompletionNode = true;
				this.restartRecovery	= true;	// force to restart in recovery mode
				this.lastIgnoredToken = TokenNameInvalid;
			}
		}
	} else {
		this.identifierLengthPtr--;
		char[] identifierName = this.identifierStack[this.identifierPtr];
		long namePositions = this.identifierPositionStack[this.identifierPtr--];
		this.intPtr--; // dimension from the variabledeclaratorid
		TypeReference type = (TypeReference) this.astStack[this.astPtr--];
		this.astLengthPtr --;
		int modifierPositions = this.intStack[this.intPtr--];
		this.intPtr--;
		Argument arg =
			new SelectionOnArgumentName(
				identifierName,
				namePositions,
				type,
				this.intStack[this.intPtr + 1] & ~ClassFileConstants.AccDeprecated); // modifiers
		arg.bits &= ~ASTNode.IsArgument;
		arg.declarationSourceStart = modifierPositions;

		// consume annotations
		int length;
		if ((length = this.expressionLengthStack[this.expressionLengthPtr--]) != 0) {
			System.arraycopy(
				this.expressionStack,
				(this.expressionPtr -= length) + 1,
				arg.annotations = new Annotation[length],
				0,
				length);
		}

		pushOnAstStack(arg);

		this.assistNode = arg;
		this.lastCheckPoint = (int) namePositions;
		this.isOrphanCompletionNode = true;

		if (!this.diet){
			this.restartRecovery	= true;	// force to restart in recovery mode
			this.lastIgnoredToken = TokenNameInvalid;
		}

		/* if incomplete method header, listLength counter will not have been reset,
			indicating that some arguments are available on the stack */
		this.listLength++;
	}
}
@Override
protected void consumeClassInstanceCreationExpressionQualifiedWithTypeArguments() {
	// ClassInstanceCreationExpression ::= Primary '.' 'new' TypeArguments SimpleName '(' ArgumentListopt ')' ClassBodyopt
	// ClassInstanceCreationExpression ::= ClassInstanceCreationExpressionName 'new' TypeArguments SimpleName '(' ArgumentListopt ')' ClassBodyopt

	QualifiedAllocationExpression alloc;
	int length;
	if (((length = this.astLengthStack[this.astLengthPtr]) == 1) && (this.astStack[this.astPtr] == null)) {

		if (this.indexOfAssistIdentifier() < 0) {
			super.consumeClassInstanceCreationExpressionQualifiedWithTypeArguments();
			return;
		}

		//NO ClassBody
		this.astPtr--;
		this.astLengthPtr--;
		alloc = new SelectionOnQualifiedAllocationExpression();
		alloc.sourceEnd = this.endPosition; //the position has been stored explicitly

		if ((length = this.expressionLengthStack[this.expressionLengthPtr--]) != 0) {
			this.expressionPtr -= length;
			System.arraycopy(
				this.expressionStack,
				this.expressionPtr + 1,
				alloc.arguments = new Expression[length],
				0,
				length);
		}

		// trick to avoid creating a selection on type reference
		char [] oldIdent = assistIdentifier();
		setAssistIdentifier(null);
		alloc.type = getTypeReference(0);
		checkForDiamond(alloc.type);

		setAssistIdentifier(oldIdent);

		length = this.genericsLengthStack[this.genericsLengthPtr--];
		this.genericsPtr -= length;
		System.arraycopy(this.genericsStack, this.genericsPtr + 1, alloc.typeArguments = new TypeReference[length], 0, length);
		this.intPtr--; // remove the position of the '<'

		//the default constructor with the correct number of argument
		//will be created and added by the TC (see createsInternalConstructorWithBinding)
		alloc.sourceStart = this.intStack[this.intPtr--];
		pushOnExpressionStack(alloc);

		this.assistNode = alloc;
		this.lastCheckPoint = alloc.sourceEnd + 1;
		if (!this.diet){
			this.restartRecovery	= true;	// force to restart in recovery mode
			this.lastIgnoredToken = TokenNameInvalid;
		}
		this.isOrphanCompletionNode = true;
	} else {
		super.consumeClassInstanceCreationExpressionQualifiedWithTypeArguments();
	}

	this.expressionLengthPtr--;
	QualifiedAllocationExpression qae =
		(QualifiedAllocationExpression) this.expressionStack[this.expressionPtr--];
	qae.enclosingInstance = this.expressionStack[this.expressionPtr];
	this.expressionStack[this.expressionPtr] = qae;
	qae.sourceStart = qae.enclosingInstance.sourceStart;
}
@Override
protected void consumeClassInstanceCreationExpressionWithTypeArguments() {
	// ClassInstanceCreationExpression ::= 'new' TypeArguments ClassType '(' ArgumentListopt ')' ClassBodyopt
	AllocationExpression alloc;
	int length;
	if (((length = this.astLengthStack[this.astLengthPtr]) == 1)
		&& (this.astStack[this.astPtr] == null)) {

		if (this.indexOfAssistIdentifier() < 0) {
			super.consumeClassInstanceCreationExpressionWithTypeArguments();
			return;
		}

		//NO ClassBody
		this.astPtr--;
		this.astLengthPtr--;
		alloc = new SelectionOnQualifiedAllocationExpression();
		alloc.sourceEnd = this.endPosition; //the position has been stored explicitly

		if ((length = this.expressionLengthStack[this.expressionLengthPtr--]) != 0) {
			this.expressionPtr -= length;
			System.arraycopy(
				this.expressionStack,
				this.expressionPtr + 1,
				alloc.arguments = new Expression[length],
				0,
				length);
		}

		// trick to avoid creating a selection on type reference
		char [] oldIdent = assistIdentifier();
		setAssistIdentifier(null);
		alloc.type = getTypeReference(0);
		checkForDiamond(alloc.type);

		setAssistIdentifier(oldIdent);

		length = this.genericsLengthStack[this.genericsLengthPtr--];
		this.genericsPtr -= length;
		System.arraycopy(this.genericsStack, this.genericsPtr + 1, alloc.typeArguments = new TypeReference[length], 0, length);
		this.intPtr--; // remove the position of the '<'

		//the default constructor with the correct number of argument
		//will be created and added by the TC (see createsInternalConstructorWithBinding)
		alloc.sourceStart = this.intStack[this.intPtr--];
		pushOnExpressionStack(alloc);

		this.assistNode = alloc;
		this.lastCheckPoint = alloc.sourceEnd + 1;
		if (!this.diet){
			this.restartRecovery	= true;	// force to restart in recovery mode
			this.lastIgnoredToken = TokenNameInvalid;
		}
		this.isOrphanCompletionNode = true;
	} else {
		super.consumeClassInstanceCreationExpressionWithTypeArguments();
	}
}
@Override
protected void consumeEnterAnonymousClassBody(boolean qualified) {
	// EnterAnonymousClassBody ::= $empty

	if (this.indexOfAssistIdentifier() < 0) {
		super.consumeEnterAnonymousClassBody(qualified);
		return;
	}

	// trick to avoid creating a selection on type reference
	char [] oldIdent = assistIdentifier();
	setAssistIdentifier(null);
	TypeReference typeReference = getTypeReference(0);
	setAssistIdentifier(oldIdent);

	TypeDeclaration anonymousType = new TypeDeclaration(this.compilationUnit.compilationResult);
	anonymousType.name = CharOperation.NO_CHAR;
	anonymousType.bits |= (ASTNode.IsAnonymousType|ASTNode.IsLocalType);
	QualifiedAllocationExpression alloc = new SelectionOnQualifiedAllocationExpression(anonymousType);
	markEnclosingMemberWithLocalType();
	pushOnAstStack(anonymousType);

	alloc.sourceEnd = this.rParenPos; //the position has been stored explicitly
	int argumentLength;
	if ((argumentLength = this.expressionLengthStack[this.expressionLengthPtr--]) != 0) {
		this.expressionPtr -= argumentLength;
		System.arraycopy(
			this.expressionStack,
			this.expressionPtr + 1,
			alloc.arguments = new Expression[argumentLength],
			0,
			argumentLength);
	}

	if (qualified) {
		this.expressionLengthPtr--;
		alloc.enclosingInstance = this.expressionStack[this.expressionPtr--];
	}

	alloc.type = typeReference;

	anonymousType.sourceEnd = alloc.sourceEnd;
	//position at the type while it impacts the anonymous declaration
	anonymousType.sourceStart = anonymousType.declarationSourceStart = alloc.type.sourceStart;
	alloc.sourceStart = this.intStack[this.intPtr--];
	pushOnExpressionStack(alloc);

	this.assistNode = alloc;
	this.lastCheckPoint = alloc.sourceEnd + 1;
	if (!this.diet){
		this.restartRecovery	= true;	// force to restart in recovery mode
		this.lastIgnoredToken = TokenNameInvalid;
		if (isIndirectlyInsideLambdaExpression())
			this.ignoreNextOpeningBrace = true;
		else
			this.currentToken = TokenNameNotAToken; // opening brace already taken into account.
		this.hasReportedError = true;
	}

	anonymousType.bodyStart = this.scanner.currentPosition;
	this.listLength = 0; // will be updated when reading super-interfaces
	// recovery
	if (this.currentElement != null){
		this.lastCheckPoint = anonymousType.bodyStart;
		this.currentElement = this.currentElement.add(anonymousType, 0);
		if (isIndirectlyInsideLambdaExpression())
			this.ignoreNextOpeningBrace = true;
		else
			this.currentToken = TokenNameNotAToken; // opening brace already taken into account.
		this.lastIgnoredToken = TokenNameInvalid;
	}
}
@Override
protected void consumeEnterVariable() {
	// EnterVariable ::= $empty
	// do nothing by default

	super.consumeEnterVariable();

	AbstractVariableDeclaration variable = (AbstractVariableDeclaration) this.astStack[this.astPtr];
	if (variable.type == this.assistNode){
		if (!this.diet && ! variable.type.isTypeNameVar(null)) {
			this.restartRecovery	= true;	// force to restart in recovery mode
			this.lastIgnoredToken = TokenNameInvalid;
		}
		this.isOrphanCompletionNode = false; // already attached inside variable decl
	}

	if (this.currentToken == TerminalToken.TokenNameEQUAL)
		pushOnElementStack(K_INSIDE_INITIALIZATION);
}

@Override
protected void consumeExitVariableWithInitialization() {
	popElement(K_INSIDE_INITIALIZATION);
	super.consumeExitVariableWithInitialization();

	// does not keep the initialization if selection is not inside
	AbstractVariableDeclaration variable = (AbstractVariableDeclaration) this.astStack[this.astPtr];
	int start = variable.declarationSourceStart;
	int end =  variable.declarationSourceEnd;
	// Keep the initialization intact, because that's the only way we are going to know the type
	if (!variable.type.isTypeNameVar(null)) {
		if ((this.selectionStart < start) &&  (this.selectionEnd < start) ||
				(this.selectionStart > end) && (this.selectionEnd > end)) {
			variable.initialization = null;
		}
	}
	triggerRecoveryUponLambdaClosure(variable, false);
}

@Override
protected void consumeFieldAccess(boolean isSuperAccess) {
	// FieldAccess ::= Primary '.' 'Identifier'
	// FieldAccess ::= 'super' '.' 'Identifier'

	if (this.indexOfAssistIdentifier() < 0) {
		super.consumeFieldAccess(isSuperAccess);
		return;
	}
	FieldReference fieldReference =
		new SelectionOnFieldReference(
			this.identifierStack[this.identifierPtr],
			this.identifierPositionStack[this.identifierPtr--]);
	this.identifierLengthPtr--;
	if (isSuperAccess) { //considerates the fieldReferenceerence beginning at the 'super' ....
		fieldReference.sourceStart = this.intStack[this.intPtr--];
		fieldReference.receiver = new SuperReference(fieldReference.sourceStart, this.endPosition);
		pushOnExpressionStack(fieldReference);
	} else { //optimize push/pop
		if ((fieldReference.receiver = this.expressionStack[this.expressionPtr]).isThis()) { //fieldReferenceerence begins at the this
			fieldReference.sourceStart = fieldReference.receiver.sourceStart;
		}
		this.expressionStack[this.expressionPtr] = fieldReference;
	}
	this.assistNode = fieldReference;
	this.lastCheckPoint = fieldReference.sourceEnd + 1;
	if (!this.diet){
		this.restartRecovery	= true;	// force to restart in recovery mode
		this.lastIgnoredToken = TokenNameInvalid;
	}
	this.isOrphanCompletionNode = true;
}
@Override
protected void consumeSingleVariableDeclarator(boolean isVarArgs) {
	if (this.indexOfAssistIdentifier() < 0 || this.parsingRecordComponents) {
		super.consumeSingleVariableDeclarator(isVarArgs);
	} else {
		boolean isReceiver = this.intStack[this.intPtr--] == 0;
	    if (isReceiver) {
	    	this.expressionPtr--;
	    	this.expressionLengthPtr --;
	    }
		this.identifierLengthPtr--;
		char[] identifierName = this.identifierStack[this.identifierPtr];
		long namePositions = this.identifierPositionStack[this.identifierPtr--];
		int extendedDimensions = this.intStack[this.intPtr--];
		Annotation [][] annotationsOnExtendedDimensions = extendedDimensions == 0 ? null : getAnnotationsOnDimensions(extendedDimensions);
		Annotation [] varArgsAnnotations = null;
		int length;
		int endOfEllipsis = 0;
		if (isVarArgs) {
			endOfEllipsis = this.intStack[this.intPtr--];
			if ((length = this.typeAnnotationLengthStack[this.typeAnnotationLengthPtr--]) != 0) {
				System.arraycopy(
					this.typeAnnotationStack,
					(this.typeAnnotationPtr -= length) + 1,
					varArgsAnnotations = new Annotation[length],
					0,
					length);
			}
		}
		int firstDimensions = this.intStack[this.intPtr--];
		TypeReference type = getTypeReference(firstDimensions);

		if (isVarArgs || extendedDimensions != 0) {
			if (isVarArgs) {
				type = augmentTypeWithAdditionalDimensions(type, 1, varArgsAnnotations != null ? new Annotation[][] { varArgsAnnotations } : null, true);
			}
			if (extendedDimensions != 0) { // combination illegal.
				type = augmentTypeWithAdditionalDimensions(type, extendedDimensions, annotationsOnExtendedDimensions, false);
			}
			type.sourceEnd = type.isParameterizedTypeReference() ? this.endStatementPosition : this.endPosition;
		}
		if (isVarArgs) {
			if (extendedDimensions == 0) {
				type.sourceEnd = endOfEllipsis;
			}
			type.bits |= ASTNode.IsVarArgs; // set isVarArgs
		}
		int modifierPositions = this.intStack[this.intPtr--];
		this.intPtr--;
		Argument arg =
			new SelectionOnArgumentName(
				identifierName,
				namePositions,
				type,
				this.intStack[this.intPtr + 1] & ~ClassFileConstants.AccDeprecated); // modifiers
		arg.declarationSourceStart = modifierPositions;

		// consume annotations
		if ((length = this.expressionLengthStack[this.expressionLengthPtr--]) != 0) {
			System.arraycopy(
				this.expressionStack,
				(this.expressionPtr -= length) + 1,
				arg.annotations = new Annotation[length],
				0,
				length);
			RecoveredType currentRecoveryType = this.currentRecoveryType();
			if (currentRecoveryType != null)
				currentRecoveryType.annotationsConsumed(arg.annotations);
		}

		pushOnAstStack(arg);

		this.assistNode = arg;
		this.lastCheckPoint = (int) namePositions;
		this.isOrphanCompletionNode = true;

		if (!this.diet){
			this.restartRecovery	= true;	// force to restart in recovery mode
			this.lastIgnoredToken = TokenNameInvalid;
		}

		/* if incomplete method header, listLength counter will not have been reset,
			indicating that some arguments are available on the stack */
		this.listLength++;
	}
}
@Override
protected void consumeGuard() {
	super.consumeGuard();
	popUntilElement(K_INSIDE_WHEN);
	popElement(K_INSIDE_WHEN);
}

@Override
protected void consumePostExpressionInIf() {
	super.consumePostExpressionInIf();
	pushOnElementStack(K_INSIDE_THEN, this.expressionPtr, this.astPtr);
}

@Override
protected void consumePostExpressionInSwitch(boolean statSwitch) {
	super.consumePostExpressionInSwitch(statSwitch);
	pushOnElementStack(statSwitch ? K_INSIDE_STATEMENT_SWITCH : K_INSIDE_EXPRESSION_SWITCH, this.expressionPtr, this.astPtr);
}

@Override
protected void consumePostExpressionInWhile() {
	super.consumePostExpressionInWhile();
	if (this.expressionStack[this.expressionPtr].containsPatternVariable()) {
		pushOnElementStack(K_POST_WHILE_EXPRESSION, this.expressionPtr, this.astPtr);
	} else {
		popUntilElement(K_INSIDE_WHILE);
		popElement(K_INSIDE_WHILE);
	}
}

@Override
protected void consumeSwitchStatementOrExpression(boolean isStmt) {
	super.consumeSwitchStatementOrExpression(isStmt);
	if (isStmt) {
		popUntilElement(K_INSIDE_STATEMENT_SWITCH);
		popElement(K_INSIDE_STATEMENT_SWITCH);
	} else {
		popUntilElement(K_INSIDE_EXPRESSION_SWITCH);
		popElement(K_INSIDE_EXPRESSION_SWITCH);
	}
}

@Override
protected void consumeStatementWhile() {
	super.consumeStatementWhile();
	if (((WhileStatement) this.astStack[this.astPtr]).condition.containsPatternVariable()) {
		popUntilElement(K_INSIDE_WHILE);
		popElement(K_INSIDE_WHILE);
	}
}

@Override
protected void consumeEnhancedForStatementHeader() {
	super.consumeEnhancedForStatementHeader();
	pushOnElementStack(K_INSIDE_FOR_EACH, this.expressionPtr, this.astPtr);
}

@Override
protected void consumeEnhancedForStatement() {
	super.consumeEnhancedForStatement();
	popUntilElement(K_INSIDE_FOR_EACH);
	popElement(K_INSIDE_FOR_EACH);
}

@Override
protected void consumeBinaryExpression(int op) {
	super.consumeBinaryExpression(op);
	if (op == AND_AND) {
		popUntilElement(K_POST_AND_AND);
		popElement(K_POST_AND_AND);
	}
	else if (op == OR_OR) {
		popUntilElement(K_POST_OR_OR);
		popElement(K_POST_OR_OR);
	}
}

@Override
protected void consumeBinaryExpressionWithName(int op) {
	super.consumeBinaryExpressionWithName(op);
	if (op == AND_AND) {
		popUntilElement(K_POST_AND_AND);
		popElement(K_POST_AND_AND);
	}
	else if (op == OR_OR) {
		popUntilElement(K_POST_OR_OR);
		popElement(K_POST_OR_OR);
	}
}

@Override
protected void consumeAssignment() {
	popElement(K_INSIDE_ASSIGNMENT);
	super.consumeAssignment();
}
@Override
protected void consumeAssignmentOperator(int pos) {
	super.consumeAssignmentOperator(pos);
	if (pos == EQUAL)
		pushOnElementStack(K_INSIDE_ASSIGNMENT, pos);
}

@Override
protected void consumeStatementDo() {
	super.consumeStatementDo();
	popUntilElement(K_INSIDE_WHILE);
	popElement(K_INSIDE_WHILE);
}

@Override
protected void consumeStatementIfNoElse() {
	super.consumeStatementIfNoElse();
	popUntilElement(K_INSIDE_IF);
	popElement(K_INSIDE_IF);
}

@Override
protected void consumeStatementIfWithElse() {
	super.consumeStatementIfWithElse();
	popUntilElement(K_INSIDE_IF);
	popElement(K_INSIDE_IF);
}

@Override
protected void consumeInsideCastExpression() {
	super.consumeInsideCastExpression();
	pushOnElementStack(K_CAST_STATEMENT);
}
@Override
protected void consumeInsideCastExpressionLL1() {
	super.consumeInsideCastExpressionLL1();
	pushOnElementStack(K_CAST_STATEMENT);
}
@Override
protected void consumeInsideCastExpressionLL1WithBounds() {
	super.consumeInsideCastExpressionLL1WithBounds();
	pushOnElementStack(K_CAST_STATEMENT);
}
@Override
protected void consumeInsideCastExpressionWithQualifiedGenerics() {
	super.consumeInsideCastExpressionWithQualifiedGenerics();
	pushOnElementStack(K_CAST_STATEMENT);
}

@Override
protected void consumeSwitchRule(SwitchRuleKind kind) {
	super.consumeSwitchRule(kind);
	if (kind == SwitchRuleKind.EXPRESSION)
		popElement(K_SWITCH_EXPRESSION_DELIMITTER);
}

@Override
protected void consumeInstanceOfExpressionWithName() {
	if (indexOfAssistIdentifier() < 0) {
		super.consumeInstanceOfExpressionWithName();
		return;
	}
	int length = this.astLengthStack[this.astLengthPtr--];
	if (length > 0) {
		Pattern pattern = (Pattern) this.astStack[this.astPtr--];
		pushOnExpressionStack(getUnspecifiedReferenceOptimized());
		consumePatternInsideInstanceof(pattern);
		if (this.expressionStack[this.expressionPtr] != this.assistNode) {
			if ((this.selectionStart >= pattern.sourceStart)
					&&  (this.selectionEnd <= pattern.sourceEnd)) {
				this.isOrphanCompletionNode = true;
				this.restartRecovery	= true;
				this.lastIgnoredToken = TokenNameInvalid;
			}
		} else if (indexOfAssistIdentifier() >= 0) {
			this.isOrphanCompletionNode = true;
			this.restartRecovery = true;
			this.lastIgnoredToken = TokenNameInvalid;
		}
	} else {
		getTypeReference(this.intStack[this.intPtr--]);
		this.isOrphanCompletionNode = true;
		this.restartRecovery = true;
		this.lastIgnoredToken = TokenNameInvalid;
	}
}
@Override
protected void consumeLambdaExpression() {
	super.consumeLambdaExpression();
	LambdaExpression expression = (LambdaExpression) this.expressionStack[this.expressionPtr];
	int arrowEnd = expression.arrowPosition();
	int arrowStart = arrowEnd - 1;
	if (this.selectionStart == arrowStart || this.selectionStart == arrowEnd) {
		if (this.selectionEnd == arrowStart || this.selectionEnd == arrowEnd) {
			this.expressionStack[this.expressionPtr] = new SelectionOnLambdaExpression(expression);
			this.assistNode = expression;
		}
	} else if (this.selectionStart == expression.sourceStart && this.selectionEnd == expression.sourceEnd) {
		SelectionOnLambdaExpression lambdaExpression = new SelectionOnLambdaExpression(expression);
		this.expressionStack[this.expressionPtr] = lambdaExpression;
		this.assistNode = lambdaExpression;
		this.lastCheckPoint = lambdaExpression.sourceEnd + 1;
	}

	popUntilElement(K_LAMBDA_EXPRESSION_DELIMITER);
	popElement(K_LAMBDA_EXPRESSION_DELIMITER);
	if (this.selectionStart >= expression.sourceStart && this.selectionEnd <= expression.sourceEnd) {
		if (!isIndirectlyInsideLambdaExpression()) {
			this.selectionNodeFoundLevel = 1;
		}
	}
}
@Override
protected void consumeReferenceExpression(ReferenceExpression referenceExpression) {
	int kolonKolonStart = this.colonColonStart;
	int kolonKolonEnd = kolonKolonStart + 1;
	this.colonColonStart = -1;
	if (this.selectionStart == kolonKolonStart || this.selectionStart == kolonKolonEnd) {
		if (this.selectionEnd == kolonKolonStart || this.selectionEnd == kolonKolonEnd) {
			referenceExpression = new SelectionOnReferenceExpression(referenceExpression, this.scanner);
		}
	}
	super.consumeReferenceExpression(referenceExpression);
}

@Override
protected void consumeLocalVariableDeclarationStatement() {
	super.consumeLocalVariableDeclarationStatement();

	// force to restart in recovery mode if the declaration contains the selection
	if (!this.diet && this.astStack[this.astPtr] instanceof LocalDeclaration) {
		LocalDeclaration localDeclaration = (LocalDeclaration) this.astStack[this.astPtr];
		if ((this.selectionStart >= localDeclaration.sourceStart)
				&&  (this.selectionEnd <= localDeclaration.sourceEnd)) {
			this.restartRecovery	= true;
			this.lastIgnoredToken = TokenNameInvalid;
		}
	}
}

@Override
protected void consumeBlockStatement() {
	// Super implementation unsuitable, so no chaining.
	checkRestartRecovery();
}

@Override
protected void consumeBlockStatements() {
	// Super implementation unsuitable, so no chaining.
	concatNodeLists();
	checkRestartRecovery();
}

protected void checkRestartRecovery() {
	if (this.selectionNodeFoundLevel == 1) {
		this.selectionNodeFoundLevel = 0;
		this.restartRecovery = true;
	}
}

@Override
protected void consumeOpenBlock() {
	super.consumeOpenBlock();
	if (this.selectionNodeFoundLevel > 0)
		this.selectionNodeFoundLevel++;
}

@Override
protected void consumeBlock() {
	super.consumeBlock();
	if (this.selectionNodeFoundLevel > 0)
		this.selectionNodeFoundLevel--;
}

@Override
protected void consumeMarkerAnnotation(boolean isTypeAnnotation) {
	int index;

	if ((index = this.indexOfAssistIdentifier()) < 0) {
		super.consumeMarkerAnnotation(isTypeAnnotation);
		return;
	}

	MarkerAnnotation markerAnnotation = null;
	int length = this.identifierLengthStack[this.identifierLengthPtr];
	TypeReference typeReference;

	/* retrieve identifiers subset and whole positions, the assist node positions
		should include the entire replaced source. */

	char[][] subset = identifierSubSet(index);
	this.identifierLengthPtr--;
	this.identifierPtr -= length;
	long[] positions = new long[length];
	System.arraycopy(
		this.identifierPositionStack,
		this.identifierPtr + 1,
		positions,
		0,
		length);

	/* build specific assist on type reference */

	if (index == 0) {
		/* assist inside first identifier */
		typeReference = createSingleAssistTypeReference(
						assistIdentifier(),
						positions[0]);
	} else {
		/* assist inside subsequent identifier */
		typeReference =	createQualifiedAssistTypeReference(
						subset,
						assistIdentifier(),
						positions);
	}
	this.assistNode = typeReference;
	this.lastCheckPoint = typeReference.sourceEnd + 1;

	markerAnnotation = new MarkerAnnotation(typeReference, this.intStack[this.intPtr--]);
	markerAnnotation.declarationSourceEnd = markerAnnotation.sourceEnd;
	if (isTypeAnnotation) {
		pushOnTypeAnnotationStack(markerAnnotation);
	} else {
		pushOnExpressionStack(markerAnnotation);
	}
}
@Override
protected void consumeMemberValuePair() {
	if (this.indexOfAssistIdentifier() < 0) {
		super.consumeMemberValuePair();
		return;
	}

	char[] simpleName = this.identifierStack[this.identifierPtr];
	long position = this.identifierPositionStack[this.identifierPtr--];
	this.identifierLengthPtr--;
	int end = (int) position;
	int start = (int) (position >>> 32);
	Expression value = this.expressionStack[this.expressionPtr--];
	this.expressionLengthPtr--;
	MemberValuePair memberValuePair = new SelectionOnNameOfMemberValuePair(simpleName, start, end, value);
	pushOnAstStack(memberValuePair);

	this.assistNode = memberValuePair;
	this.lastCheckPoint = memberValuePair.sourceEnd + 1;


}
@Override
protected void consumeMethodInvocationName() {
	// MethodInvocation ::= Name '(' ArgumentListopt ')'

	// when the name is only an identifier...we have a message send to "this" (implicit)

	char[] selector = this.identifierStack[this.identifierPtr];
	int accessMode;
	if(selector == assistIdentifier()) {
		if(CharOperation.equals(selector, SUPER)) {
			accessMode = ExplicitConstructorCall.Super;
		} else if(CharOperation.equals(selector, THIS)) {
			accessMode = ExplicitConstructorCall.This;
		} else {
			super.consumeMethodInvocationName();
			return;
		}
	} else {
		super.consumeMethodInvocationName();
		if (requireExtendedRecovery()) {
			// https://bugs.eclipse.org/bugs/show_bug.cgi?id=430572, compensate for the hacks elsewhere where super/this gets treated as identifier. See getUnspecifiedReference
			if (this.astPtr >= 0 && this.astStack[this.astPtr] == this.assistNode && this.assistNode instanceof ThisReference) {
				MessageSend messageSend = (MessageSend) this.expressionStack[this.expressionPtr];
				if (messageSend.receiver instanceof SingleNameReference) {
					SingleNameReference snr = (SingleNameReference) messageSend.receiver;
					if (snr.token == CharOperation.NO_CHAR) { // dummy reference created by getUnspecifiedReference ???
						messageSend.receiver = (Expression) this.astStack[this.astPtr--];
					}
				}
			}
		}
		return;
	}

	final ExplicitConstructorCall constructorCall = new SelectionOnExplicitConstructorCall(accessMode);
	constructorCall.sourceEnd = this.rParenPos;
	constructorCall.sourceStart = (int) (this.identifierPositionStack[this.identifierPtr] >>> 32);
	int length;
	if ((length = this.expressionLengthStack[this.expressionLengthPtr--]) != 0) {
		this.expressionPtr -= length;
		System.arraycopy(this.expressionStack, this.expressionPtr + 1, constructorCall.arguments = new Expression[length], 0, length);
	}

	if (!this.diet){
		pushOnAstStack(constructorCall);
		this.restartRecovery	= true;	// force to restart in recovery mode
		this.lastIgnoredToken = TokenNameInvalid;
	} else {
		pushOnExpressionStack(new Expression(){
			@Override
			public TypeBinding resolveType(BlockScope scope) {
				constructorCall.resolve(scope);
				return null;
			}
			@Override
			public StringBuilder printExpression(int indent, StringBuilder output) {
				return output;
			}
		});
	}
	this.assistNode = constructorCall;
	this.lastCheckPoint = constructorCall.sourceEnd + 1;
	this.isOrphanCompletionNode = true;
}
@Override
protected void consumeMethodInvocationPrimary() {
	//optimize the push/pop
	//MethodInvocation ::= Primary '.' 'Identifier' '(' ArgumentListopt ')'

	char[] selector = this.identifierStack[this.identifierPtr];
	int accessMode;
	if(selector == assistIdentifier()) {
		if(CharOperation.equals(selector, SUPER)) {
			accessMode = ExplicitConstructorCall.Super;
		} else if(CharOperation.equals(selector, THIS)) {
			accessMode = ExplicitConstructorCall.This;
		} else {
			super.consumeMethodInvocationPrimary();
			return;
		}
	} else {
		super.consumeMethodInvocationPrimary();
		return;
	}

	final ExplicitConstructorCall constructorCall = new SelectionOnExplicitConstructorCall(accessMode);
	constructorCall.sourceEnd = this.rParenPos;
	int length;
	if ((length = this.expressionLengthStack[this.expressionLengthPtr--]) != 0) {
		this.expressionPtr -= length;
		System.arraycopy(this.expressionStack, this.expressionPtr + 1, constructorCall.arguments = new Expression[length], 0, length);
	}
	constructorCall.qualification = this.expressionStack[this.expressionPtr--];
	constructorCall.sourceStart = constructorCall.qualification.sourceStart;

	if (!this.diet){
		pushOnAstStack(constructorCall);
		this.restartRecovery	= true;	// force to restart in recovery mode
		this.lastIgnoredToken = TokenNameInvalid;
	} else {
		pushOnExpressionStack(new Expression(){
			@Override
			public TypeBinding resolveType(BlockScope scope) {
				constructorCall.resolve(scope);
				return null;
			}
			@Override
			public StringBuilder printExpression(int indent, StringBuilder output) {
				return output;
			}
		});
	}

	this.assistNode = constructorCall;
	this.lastCheckPoint = constructorCall.sourceEnd + 1;
	this.isOrphanCompletionNode = true;
}
@Override
protected void consumeNormalAnnotation(boolean isTypeAnnotation) {
	int index;

	if ((index = this.indexOfAssistIdentifier()) < 0) {
		super.consumeNormalAnnotation(isTypeAnnotation);
		return;
	}

	NormalAnnotation normalAnnotation = null;
	int length = this.identifierLengthStack[this.identifierLengthPtr];
	TypeReference typeReference;

	/* retrieve identifiers subset and whole positions, the assist node positions
		should include the entire replaced source. */

	char[][] subset = identifierSubSet(index);
	this.identifierLengthPtr--;
	this.identifierPtr -= length;
	long[] positions = new long[length];
	System.arraycopy(
		this.identifierPositionStack,
		this.identifierPtr + 1,
		positions,
		0,
		length);

	/* build specific assist on type reference */

	if (index == 0) {
		/* assist inside first identifier */
		typeReference = createSingleAssistTypeReference(
						assistIdentifier(),
						positions[0]);
	} else {
		/* assist inside subsequent identifier */
		typeReference =	createQualifiedAssistTypeReference(
						subset,
						assistIdentifier(),
						positions);
	}
	this.assistNode = typeReference;
	this.lastCheckPoint = typeReference.sourceEnd + 1;

	normalAnnotation = new NormalAnnotation(typeReference, this.intStack[this.intPtr--]);
	if ((length = this.astLengthStack[this.astLengthPtr--]) != 0) {
		System.arraycopy(
			this.astStack,
			(this.astPtr -= length) + 1,
			normalAnnotation.memberValuePairs = new MemberValuePair[length],
			0,
			length);
	}
	normalAnnotation.declarationSourceEnd = this.rParenPos;
	if (isTypeAnnotation) {
		pushOnTypeAnnotationStack(normalAnnotation);
	} else {
		pushOnExpressionStack(normalAnnotation);
	}
}
@Override
protected void consumeSingleMemberAnnotation(boolean isTypeAnnotation) {
	int index;

	if ((index = this.indexOfAssistIdentifier()) < 0) {
		super.consumeSingleMemberAnnotation(isTypeAnnotation);
		return;
	}

	SingleMemberAnnotation singleMemberAnnotation = null;
	int length = this.identifierLengthStack[this.identifierLengthPtr];
	TypeReference typeReference;

	/* retrieve identifiers subset and whole positions, the assist node positions
		should include the entire replaced source. */

	char[][] subset = identifierSubSet(index);
	this.identifierLengthPtr--;
	this.identifierPtr -= length;
	long[] positions = new long[length];
	System.arraycopy(
		this.identifierPositionStack,
		this.identifierPtr + 1,
		positions,
		0,
		length);

	/* build specific assist on type reference */

	if (index == 0) {
		/* assist inside first identifier */
		typeReference = createSingleAssistTypeReference(
						assistIdentifier(),
						positions[0]);
	} else {
		/* assist inside subsequent identifier */
		typeReference =	createQualifiedAssistTypeReference(
						subset,
						assistIdentifier(),
						positions);
	}
	this.assistNode = typeReference;
	this.lastCheckPoint = typeReference.sourceEnd + 1;

	singleMemberAnnotation = new SingleMemberAnnotation(typeReference, this.intStack[this.intPtr--]);
	singleMemberAnnotation.memberValue = this.expressionStack[this.expressionPtr--];
	this.expressionLengthPtr--;
	singleMemberAnnotation.declarationSourceEnd = this.rParenPos;
	if (isTypeAnnotation) {
		pushOnTypeAnnotationStack(singleMemberAnnotation);
	} else {
		pushOnExpressionStack(singleMemberAnnotation);
	}
}
@Override
protected void consumeStaticImportOnDemandDeclarationName() {
	// TypeImportOnDemandDeclarationName ::= 'import' 'static' Name '.' '*'
	/* push an ImportRef build from the last name
	stored in the identifier stack. */

	int index;

	/* no need to take action if not inside assist identifiers */
	if ((index = indexOfAssistIdentifier()) < 0) {
		super.consumeStaticImportOnDemandDeclarationName();
		return;
	}
	/* retrieve identifiers subset and whole positions, the assist node positions
		should include the entire replaced source. */
	int length = this.identifierLengthStack[this.identifierLengthPtr];
	char[][] subset = identifierSubSet(index+1); // include the assistIdentifier
	this.identifierLengthPtr--;
	this.identifierPtr -= length;
	long[] positions = new long[length];
	System.arraycopy(
		this.identifierPositionStack,
		this.identifierPtr + 1,
		positions,
		0,
		length);

	/* build specific assist node on import statement */
	ImportReference reference = createAssistImportReference(subset, positions, ClassFileConstants.AccStatic);
	reference.bits |= ASTNode.OnDemand;
	// star end position
	reference.trailingStarPosition = this.intStack[this.intPtr--];
	this.assistNode = reference;
	this.lastCheckPoint = reference.sourceEnd + 1;

	pushOnAstStack(reference);

	if (this.currentToken == TokenNameSEMICOLON){
		reference.declarationSourceEnd = this.scanner.currentPosition - 1;
	} else {
		reference.declarationSourceEnd = (int) positions[length-1];
	}
	//endPosition is just before the ;
	reference.declarationSourceStart = this.intStack[this.intPtr--];
	// flush annotations defined prior to import statements
	reference.declarationSourceEnd = flushCommentsDefinedPriorTo(reference.declarationSourceEnd);

	// recovery
	if (this.currentElement != null){
		this.lastCheckPoint = reference.declarationSourceEnd+1;
		this.currentElement = this.currentElement.add(reference, 0);
		this.lastIgnoredToken = TokenNameInvalid;
		this.restartRecovery = true; // used to avoid branching back into the regular automaton
	}
}
@Override
protected void consumeToken(TerminalToken token) {
	TerminalToken lastToken = this.previousToken; // before super.consumeToken tramples on it
	boolean betweenCaseAndColonOrArrow = topKnownElementKind(SELECTION_OR_ASSIST_PARSER) == K_BETWEEN_CASE_AND_COLONORARROW; // before super.consumeToken tramples on it
	super.consumeToken(token);

	// if in a method or if in a field initializer
	if (isInsideMethod() || isInsideFieldInitialization()) {
		switch (token) {
			case TokenNamecase :
				pushOnElementStack(K_BETWEEN_CASE_AND_COLONORARROW, this.expressionPtr);
				break;
			case TokenNameCOMMA :
				if (this.scanner.atMultiCaseComma()) {
					switch (topKnownElementKind(SELECTION_OR_ASSIST_PARSER)) {
						// for multi constant case stmt
						// case MONDAY, FRIDAY
						// if there's a comma, ignore the previous expression (constant)
						// Which doesn't matter for the next constant
						case K_BETWEEN_CASE_AND_COLONORARROW:
							this.expressionPtr--;
							this.expressionLengthStack[this.expressionLengthPtr]--;
					}
				}
				break;
			case TokenNameCaseArrow:
			case TokenNameCOLON:
				if(topKnownElementKind(SELECTION_OR_ASSIST_PARSER) == K_BETWEEN_CASE_AND_COLONORARROW) {
					popElement(K_BETWEEN_CASE_AND_COLONORARROW);
					if (token == TokenNameCaseArrow)
						pushOnElementStack(K_SWITCH_EXPRESSION_DELIMITTER);
				}
				break;
			case TokenNamereturn:
				pushOnElementStack(K_INSIDE_RETURN_STATEMENT, this.bracketDepth);
				break;
			case TokenNameSEMICOLON:
				switch(topKnownElementKind(SELECTION_OR_ASSIST_PARSER)) {
					case K_INSIDE_RETURN_STATEMENT :
						if(topKnownElementInfo(SELECTION_OR_ASSIST_PARSER) == this.bracketDepth) {
							popElement(K_INSIDE_RETURN_STATEMENT);
						}
						break;
				}
				break;
			case TokenNameelse:
				pushOnElementStack(K_INSIDE_ELSE, this.expressionPtr, this.astPtr);
				break;
			case TokenNameAND_AND:
				pushOnElementStack(K_POST_AND_AND, this.expressionPtr);
				break;
			case TokenNameOR_OR:
				pushOnElementStack(K_POST_OR_OR, this.expressionPtr);
				break;
			case TokenNameRestrictedIdentifierWhen:
				pushOnElementStack(K_INSIDE_WHEN);
				break;
			case TokenNameLPAREN:
				if (lastToken == TokenNameif) {
					pushOnElementStack(K_INSIDE_IF, this.expressionPtr, this.astPtr);
				} else if (lastToken == TokenNamewhile) {
					pushOnElementStack(K_INSIDE_WHILE, this.expressionPtr, this.astPtr);
				} else if (lastToken == TokenNameIdentifier) {
					if (betweenCaseAndColonOrArrow  && topKnownElementKind(SELECTION_OR_ASSIST_PARSER) == K_SELECTOR) {
						popElement(K_SELECTOR); // 'case' 'ID(' is *not* the start of an invocation
					}
				}
				break;
			default:
				break;
		}
	}
}
@Override
protected void consumeTypeImportOnDemandDeclarationName() {
	// TypeImportOnDemandDeclarationName ::= 'import' Name '.' '*'
	/* push an ImportRef build from the last name
	stored in the identifier stack. */

	int index;

	/* no need to take action if not inside assist identifiers */
	if ((index = indexOfAssistIdentifier()) < 0) {
		super.consumeTypeImportOnDemandDeclarationName();
		return;
	}
	/* retrieve identifiers subset and whole positions, the assist node positions
		should include the entire replaced source. */
	int length = this.identifierLengthStack[this.identifierLengthPtr];
	char[][] subset = identifierSubSet(index+1); // include the assistIdentifier
	this.identifierLengthPtr--;
	this.identifierPtr -= length;
	long[] positions = new long[length];
	System.arraycopy(
		this.identifierPositionStack,
		this.identifierPtr + 1,
		positions,
		0,
		length);

	/* build specific assist node on import statement */
	ImportReference reference = createAssistImportReference(subset, positions, ClassFileConstants.AccDefault);
	reference.bits |= ASTNode.OnDemand;
	// star end position
	reference.trailingStarPosition = this.intStack[this.intPtr--];
	this.assistNode = reference;
	this.lastCheckPoint = reference.sourceEnd + 1;

	pushOnAstStack(reference);

	if (this.currentToken == TokenNameSEMICOLON){
		reference.declarationSourceEnd = this.scanner.currentPosition - 1;
	} else {
		reference.declarationSourceEnd = (int) positions[length-1];
	}
	//endPosition is just before the ;
	reference.declarationSourceStart = this.intStack[this.intPtr--];
	// flush comments defined prior to import statements
	reference.declarationSourceEnd = flushCommentsDefinedPriorTo(reference.declarationSourceEnd);

	// recovery
	if (this.currentElement != null){
		this.lastCheckPoint = reference.declarationSourceEnd+1;
		this.currentElement = this.currentElement.add(reference, 0);
		this.lastIgnoredToken = TokenNameInvalid;
		this.restartRecovery = true; // used to avoid branching back into the regular automaton
	}
}
@Override
protected SelectionParser createSnapShotParser() {
	return new SelectionParser(this.problemReporter);
}
@Override
public ImportReference createAssistPackageVisibilityReference(char[][] tokens, long[] positions){
	return new SelectionOnPackageVisibilityReference(tokens, positions);
}
@Override
public ImportReference createAssistImportReference(char[][] tokens, long[] positions, int mod){
	return new SelectionOnImportReference(tokens, positions, mod);
}
@Override
public ModuleDeclaration createAssistModuleDeclaration(CompilationResult compilationResult, char[][] tokens,
		long[] positions) {
	return new SelectionOnModuleDeclaration(compilationResult, tokens, positions);
}
@Override
public ImportReference createAssistPackageReference(char[][] tokens, long[] positions){
	return new SelectionOnPackageReference(tokens, positions);
}
@Override
protected JavadocParser createJavadocParser() {
	return new SelectionJavadocParser(this);
}
@Override
protected LocalDeclaration createLocalDeclaration(char[] assistName,int sourceStart,int sourceEnd) {
	if (this.indexOfAssistIdentifier() < 0) {
		return super.createLocalDeclaration(assistName, sourceStart, sourceEnd);
	} else {
		SelectionOnLocalName local = new SelectionOnLocalName(assistName, sourceStart, sourceEnd);
		this.assistNode = local;
		this.lastCheckPoint = sourceEnd + 1;
		return local;
	}
}
@Override
public NameReference createQualifiedAssistNameReference(char[][] previousIdentifiers, char[] assistName, long[] positions){
	return new SelectionOnQualifiedNameReference(
					previousIdentifiers,
					assistName,
					positions);
}
@Override
public TypeReference createQualifiedAssistTypeReference(char[][] previousIdentifiers, char[] assistName, long[] positions){
	return new SelectionOnQualifiedTypeReference(
					previousIdentifiers,
					assistName,
					positions);
}
@Override
public TypeReference createParameterizedQualifiedAssistTypeReference(
		char[][] tokens, TypeReference[][] typeArguments, char[] assistname, TypeReference[] assistTypeArguments, long[] positions) {
	return new SelectionOnParameterizedQualifiedTypeReference(tokens, assistname, typeArguments, assistTypeArguments, positions);

}
@Override
public NameReference createSingleAssistNameReference(char[] assistName, long position) {
	return new SelectionOnSingleNameReference(assistName, position);
}
@Override
public TypeReference createSingleAssistTypeReference(char[] assistName, long position) {
	return new SelectionOnSingleTypeReference(assistName, position);
}
@Override
public TypeReference createParameterizedSingleAssistTypeReference(TypeReference[] typeArguments, char[] assistName, long position) {
	return new SelectionOnParameterizedSingleTypeReference(assistName, typeArguments, position);
}
public CompilationUnitDeclaration dietParse(ICompilationUnit sourceUnit, CompilationResult compilationResult, int start, int end) {

	this.selectionStart = start;
	this.selectionEnd = end;
	SelectionScanner selectionScanner = (SelectionScanner)this.scanner;
	selectionScanner.selectionIdentifier = null;
	selectionScanner.selectionStart = start;
	selectionScanner.selectionEnd = end;
	return this.dietParse(sourceUnit, compilationResult);
}
@Override
protected NameReference getUnspecifiedReference(boolean rejectTypeAnnotations) {
	/* build a (unspecified) NameReference which may be qualified*/

	int completionIndex;

	/* no need to take action if not inside completed identifiers */
	if ((completionIndex = indexOfAssistIdentifier()) < 0) {
		return super.getUnspecifiedReference(rejectTypeAnnotations);
	}

	if (rejectTypeAnnotations) {
		consumeNonTypeUseName();
	}
	int length = this.identifierLengthStack[this.identifierLengthPtr];
	if (CharOperation.equals(assistIdentifier(), SUPER)){
		Reference reference;
		if (completionIndex > 0){ // qualified super
			// discard 'super' from identifier stacks
			// There is some voodoo going on here in combination with SelectionScanne#scanIdentifierOrKeyword, do in Rome as Romans do and leave the stacks at the right depth.
			this.identifierLengthStack[this.identifierLengthPtr] = completionIndex;
			int ptr = this.identifierPtr -= (length - completionIndex);
			pushOnGenericsLengthStack(0);
			pushOnGenericsIdentifiersLengthStack(this.identifierLengthStack[this.identifierLengthPtr]);
			for (int i = 0; i < completionIndex; i++) {
				pushOnTypeAnnotationLengthStack(0);
			}
			reference =
				new SelectionOnQualifiedSuperReference(
					getTypeReference(0),
					(int)(this.identifierPositionStack[ptr+1] >>> 32),
					(int) this.identifierPositionStack[ptr+1]);
		} else { // standard super
			this.identifierPtr -= length;
			this.identifierLengthPtr--;
			reference = new SelectionOnSuperReference((int)(this.identifierPositionStack[this.identifierPtr+1] >>> 32), (int) this.identifierPositionStack[this.identifierPtr+1]);
		}
		pushOnAstStack(reference);
		this.assistNode = reference;
		this.lastCheckPoint = reference.sourceEnd + 1;
		if (!this.diet || this.dietInt != 0){
			this.restartRecovery	= true;	// force to restart in recovery mode
			this.lastIgnoredToken = TokenNameInvalid;
		}
		this.isOrphanCompletionNode = true;
		return new SingleNameReference(CharOperation.NO_CHAR, 0); // dummy reference
	}
	NameReference nameReference;
	/* retrieve identifiers subset and whole positions, the completion node positions
		should include the entire replaced source. */
	char[][] subset = identifierSubSet(completionIndex);
	this.identifierLengthPtr--;
	this.identifierPtr -= length;
	long[] positions = new long[length];
	System.arraycopy(
		this.identifierPositionStack,
		this.identifierPtr + 1,
		positions,
		0,
		length);
	/* build specific completion on name reference */
	if (completionIndex == 0) {
		/* completion inside first identifier */
		nameReference = createSingleAssistNameReference(assistIdentifier(), positions[0]);
	} else {
		/* completion inside subsequent identifier */
		nameReference = createQualifiedAssistNameReference(subset, assistIdentifier(), positions);
	}
	this.assistNode = nameReference;
	this.lastCheckPoint = nameReference.sourceEnd + 1;
	if (!this.diet){
		this.restartRecovery	= true;	// force to restart in recovery mode
		this.lastIgnoredToken = TokenNameInvalid;
	}
	this.isOrphanCompletionNode = true;
	return nameReference;
}
/*
 * Copy of code from superclass with the following change:
 * In the case of qualified name reference if the cursor location is on the
 * qualified name reference, then create a CompletionOnQualifiedNameReference
 * instead.
 */
@Override
protected NameReference getUnspecifiedReferenceOptimized() {

	int index = indexOfAssistIdentifier();
	NameReference reference = super.getUnspecifiedReferenceOptimized();

	if (index >= 0){
		if (!this.diet){
			this.restartRecovery	= true;	// force to restart in recovery mode
			this.lastIgnoredToken = TokenNameInvalid;
		}
		this.isOrphanCompletionNode = true;
	}
	return reference;
}
@Override
public void initializeScanner(){
	this.scanner = new SelectionScanner(this.options.sourceLevel, this.options.enablePreviewFeatures);
}
@Override
public ReferenceExpression newReferenceExpression() {
	char[] selector = this.identifierStack[this.identifierPtr];
	if (selector != assistIdentifier()){
		return super.newReferenceExpression();
	}
	ReferenceExpression referenceExpression = new SelectionOnReferenceExpressionName(this.scanner);
	this.assistNode = referenceExpression;
	return referenceExpression;
}
@Override
protected MessageSend newMessageSend() {
	// '(' ArgumentListopt ')'
	// the arguments are on the expression stack

	char[] selector = this.identifierStack[this.identifierPtr];
	if (selector != assistIdentifier()){
		return super.newMessageSend();
	}
	MessageSend messageSend = new SelectionOnMessageSend();
	int length;
	if ((length = this.expressionLengthStack[this.expressionLengthPtr--]) != 0) {
		this.expressionPtr -= length;
		System.arraycopy(
			this.expressionStack,
			this.expressionPtr + 1,
			messageSend.arguments = new Expression[length],
			0,
			length);
	}
	this.assistNode = messageSend;
	if (!this.diet){
		// Don't restart recovery, not yet, until variable decl statement has been consumed.
		// This is to ensure chained method invocations are taken into account for resolution.
		this.selectionNodeFoundLevel = 1;
		this.lastIgnoredToken = TokenNameInvalid;
	}

	this.isOrphanCompletionNode = true;
	return messageSend;
}
@Override
protected MessageSend newMessageSendWithTypeArguments() {
	char[] selector = this.identifierStack[this.identifierPtr];
	if (selector != assistIdentifier()){
		return super.newMessageSendWithTypeArguments();
	}
	MessageSend messageSend = new SelectionOnMessageSend();
	int length;
	if ((length = this.expressionLengthStack[this.expressionLengthPtr--]) != 0) {
		this.expressionPtr -= length;
		System.arraycopy(
			this.expressionStack,
			this.expressionPtr + 1,
			messageSend.arguments = new Expression[length],
			0,
			length);
	}
	this.assistNode = messageSend;
	if (!this.diet){
		// Don't restart recovery, not yet, until variable decl statement has been consumed.
		// This is to ensure chained method invocations are taken into account for resolution.
		this.selectionNodeFoundLevel = 1;
		this.lastIgnoredToken = TokenNameInvalid;
	}

	this.isOrphanCompletionNode = true;
	return messageSend;
}

@Override
public CompilationUnitDeclaration parse(ICompilationUnit sourceUnit, CompilationResult compilationResult, int start, int end) {

	if (end == -1) return super.parse(sourceUnit, compilationResult, start, end);

	this.selectionStart = start;
	this.selectionEnd = end;
	SelectionScanner selectionScanner = (SelectionScanner)this.scanner;
	selectionScanner.selectionIdentifier = null;
	selectionScanner.selectionStart = start;
	selectionScanner.selectionEnd = end;
	return super.parse(sourceUnit, compilationResult, -1, -1/*parse without reseting the scanner*/);
}

@Override
protected boolean restartRecovery() {
	if (requireExtendedRecovery() || this.selectionNodeFoundLevel > 0 || this.parsingEmbeddedExpression)
		return false;
	boolean deferRestartOnLocalType = false;
	for (int i = 0; i <= this.elementPtr; i++) {
		switch (this.elementKindStack[i]) {
			case K_INSIDE_STATEMENT_SWITCH:
			case K_INSIDE_EXPRESSION_SWITCH:
			case K_INSIDE_IF:
			case K_INSIDE_WHILE:
			case K_INSIDE_FOR_EACH:
				deferRestartOnLocalType = true;
				break;
			case K_TYPE_DELIMITER:
				if (deferRestartOnLocalType)
					return false;
				break;
		}
	}

	return super.restartRecovery();
}

@Override
protected int cookedAstPtr() {
	for (int i = 0; i <= this.elementPtr; i++) {
		switch (this.elementKindStack[i]) {
			case K_INSIDE_STATEMENT_SWITCH:
			case K_INSIDE_EXPRESSION_SWITCH:
			case K_INSIDE_IF:
			case K_INSIDE_WHILE:
				if (this.assistNode != null)
					this.isOrphanCompletionNode = true; // buried deep, needs to be dug up and attached.
				return (int) this.elementObjectInfoStack[i];
		}
	}
	return super.cookedAstPtr();
}

/*
 * Reset context so as to resume to regular parse loop
 * If unable to reset for resuming, answers false.
 *
 * Move checkpoint location, reset internal stacks and
 * decide which grammar goal is activated.
 */
@Override
protected int resumeAfterRecovery() {

	/* if reached assist node inside method body, but still inside nested type,
		should continue in diet mode until the end of the method body */
	if (this.assistNode != null
		&& !(this.referenceContext instanceof CompilationUnitDeclaration)){
		this.currentElement.preserveEnclosingBlocks();
		if (this.selectionNodeFoundLevel > 0) {
			if (this.unstackedAct != ERROR_ACTION) {
				return RESUME;
			}
		}
		if (requireExtendedRecovery()) {
			if (this.unstackedAct != ERROR_ACTION) {
				return RESUME;
			}
			return super.resumeAfterRecovery();
		}
		if (this.currentElement.enclosingType() == null) {
			if (!(this.currentElement instanceof RecoveredType)) {
				resetStacks();
				return HALT;
			}

			RecoveredType recoveredType = (RecoveredType) this.currentElement;
			if (recoveredType.typeDeclaration != null && recoveredType.typeDeclaration.allocation == this.assistNode) {
				resetStacks();
				return HALT;
			}
		}
	}
	return super.resumeAfterRecovery();
}

public void selectionIdentifierCheck(){
	if (checkRecoveredType()) return;
}
@Override
public void setAssistIdentifier(char[] assistIdent){
	((SelectionScanner)this.scanner).selectionIdentifier = assistIdent;
}
/*
 * Update recovery state based on current parser/scanner state
 */
@Override
protected void updateRecoveryState() {

	/* expose parser state to recovery state */
	this.currentElement.updateFromParserState();

	/* may be able to retrieve completionNode as an orphan, and then attach it */
	selectionIdentifierCheck();
	attachOrphanCompletionNode();

	// if an assist node has been found and a recovered element exists,
	// mark enclosing blocks as to be preserved
	if (this.assistNode != null && this.currentElement != null) {
		this.currentElement.preserveEnclosingBlocks();
	}

	/* check and update recovered state based on current token,
		this action is also performed when shifting token after recovery
		got activated once.
	*/
	recoveryTokenCheck();
}
@Override
protected Argument typeElidedArgument() {
	char[] selector = this.identifierStack[this.identifierPtr];
	if (selector != assistIdentifier()){
		return super.typeElidedArgument();
	}
	this.identifierLengthPtr--;
	char[] identifierName = this.identifierStack[this.identifierPtr];
	long namePositions = this.identifierPositionStack[this.identifierPtr--];

	Argument argument =
		new SelectionOnArgumentName(
			identifierName,
			namePositions,
			null, // elided type
			ClassFileConstants.AccDefault,
			true);
	argument.declarationSourceStart = (int) (namePositions >>> 32);
	this.assistNode = argument;
	return argument;
}
@Override
public  String toString() {
	String s = Util.EMPTY_STRING;
	s = s + "elementKindStack : int[] = {"; //$NON-NLS-1$
	for (int i = 0; i <= this.elementPtr; i++) {
		s = s + String.valueOf(this.elementKindStack[i]) + ","; //$NON-NLS-1$
	}
	s = s + "}\n"; //$NON-NLS-1$
	s = s + "elementInfoStack : int[] = {"; //$NON-NLS-1$
	for (int i = 0; i <= this.elementPtr; i++) {
		s = s + String.valueOf(this.elementInfoStack[i]) + ","; //$NON-NLS-1$
	}
	s = s + "}\n"; //$NON-NLS-1$
	return s + super.toString();
}
@Override
public ModuleReference createAssistModuleReference(int index) {
	// ignore index, all segments of the module name are part of a single identifier.
	/* retrieve identifiers subset and whole positions, the assist node positions
	should include the entire replaced source. */
	int length;
	char[][] tokens = new char[length = this.identifierLengthStack[this.identifierLengthPtr--]][];
	this.identifierPtr -= length;
	long[] positions = new long[length];
	System.arraycopy(this.identifierStack, this.identifierPtr + 1, tokens, 0, length);
	System.arraycopy(this.identifierPositionStack, this.identifierPtr + 1, positions, 0, length);
	return new SelectionOnModuleReference(tokens, positions);
}
}
