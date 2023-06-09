/*
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * This file is part of Cosmic IDE.
 * Cosmic IDE is a free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * Cosmic IDE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 */
package org.cosmicide.completion.java.parser

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.ImportDeclaration
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.intellij.core.JavaCoreApplicationEnvironment
import com.intellij.core.JavaCoreProjectEnvironment
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.vfs.impl.VirtualFileManagerImpl
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiImportList
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiImportStaticReferenceElement
import com.intellij.psi.PsiImportStaticStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.impl.source.tree.TreeCopyHandler
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import org.cosmicide.completion.java.parser.cache.SymbolCacher
import org.cosmicide.completion.java.parser.cache.qualifiedName
import org.cosmicide.rewrite.editor.EditorCompletionItem
import org.cosmicide.rewrite.util.FileUtil
import org.jetbrains.kotlin.cli.common.environment.setIdeaIoUseFallback
import org.jetbrains.kotlin.cli.jvm.compiler.setupIdeaStandaloneExecution
import java.util.logging.Logger


class CompletionProvider {

    private val logger = Logger.getLogger(this::javaClass.name)

    private val environment by lazy {
        JavaCoreProjectEnvironment({ logger.info("JavaCoreProjectEnvironment disposed") },
            JavaCoreApplicationEnvironment { logger.info("JavaCoreApplicationEnvironment disposed") })
    }
    private val symbolCacher = SymbolCacher(FileUtil.classpathDir.resolve("android.jar")).apply {
        loadClassesFromJar()
    }

    private val fileFactory by lazy {
        PsiFileFactory.getInstance(environment.project)
    }

    init {
        setIdeaIoUseFallback()
        setupIdeaStandaloneExecution()
        registerExtensions()

    }


    private fun getElementType(psiElement: PsiElement): String {
        return psiElement.javaClass.simpleName
    }

    fun complete(source: String?, fileName: String?, index: Int): List<EditorCompletionItem> {
        environment.addJarToClassPath(FileUtil.classpathDir.resolve("android.jar"))
        val psiFile = fileFactory.createFileFromText(fileName!!, JavaFileType.INSTANCE, source!!)

        // Find the element at the specified position
        val element = findElementAtOffset(psiFile, index)
        println("Element: $element")
        if (element == null) {
            println("Element is null")
            return emptyList()
        }

        val completionItems = mutableListOf<EditorCompletionItem>()
        println("Prefix: ${element.text}")

        println("Element is ${getElementType(element)}")
        val isImported = isImportedClass(element)
        println("Imported already $isImported")
        if (isImportStatementContext(element)) {
            println("Import statement context")
            val packageName =
                getFullyQualifiedPackageName(element)!! + if (element.text.endsWith(".")) "." else ""
            println("Package name: $packageName")
            symbolCacher.getClasses()
                .filter {
                    val bool = it.key.startsWith(packageName) && it.key.substringAfter(packageName)
                        .contains('.').not()
                    println("condition: $bool, class: ${it.key}}")
                    bool
                }.map {
                    completionItems.add(
                        EditorCompletionItem(
                            it.value.qualifiedName(),
                            it.key.substringBeforeLast('.'),
                            0,
                            it.value.qualifiedName()
                        ).kind(CompletionItemKind.Class)
                    )
                }
            if (packageName.endsWith('.')) {
                val mPackage = packageName.substringBeforeLast('.')
                symbolCacher.getPackages()
                    .filter {
                        val parentPkgName = it.key.substringBeforeLast('.')
                        println("match : $parentPkgName")
                        val matches = parentPkgName == mPackage
                        println("Matches: $matches, package: ${it.key}")
                        matches
                    }
                    .map {
                        val toAdd = it.key.substringAfterLast('.')
                        completionItems.add(
                            EditorCompletionItem(
                                toAdd,
                                it.key.substringBeforeLast('.'),
                                0,
                                toAdd
                            ).kind(CompletionItemKind.Module)
                        )
                    }
            }
            return completionItems
        }

        val items = symbolCacher.filterClassNames(element.text)
        println("Items: $items")
        for (clazz in items) {
            val item = EditorCompletionItem(
                clazz.value,
                clazz.key,
                element.textLength,
                clazz.value
            ).kind(CompletionItemKind.Class)
            val qualifiedName = clazz.key + "." + clazz.value
            println("Qualified name: $qualifiedName")
            if (!isImportedClass(psiFile, qualifiedName)) {
                println("Not imported")
                /*item.setOnComplete {
                    it.replace(0, it.length, addImport(psiFile, qualifiedName))
                    println("Added import statement")
                }*/
            }
            completionItems.add(item)
        }

        for (keyword in javaKeywords) {
            if (keyword.startsWith(element.text)) {
                completionItems.add(
                    EditorCompletionItem(
                        keyword,
                        "KEYWORD",
                        element.textLength,
                        keyword
                    ).kind(CompletionItemKind.Keyword)
                )
            }
        }
        return completionItems
    }

    private fun isImportedClass(element: PsiElement): Boolean {
        val psiFile = element.containingFile
        if (psiFile is PsiJavaFile) {
            val importList = psiFile.importList
            if (importList != null) {
                val importStatements = importList.importStatements
                val className = element.text
                for (importStatement in importStatements) {
                    val importedClassName = importStatement.qualifiedName
                    println("Imported class: $importedClassName")
                    if (importedClassName?.substringAfterLast('.') == className) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun isImportedClass(psiFile: PsiFile, className: String): Boolean {
        if (psiFile is PsiJavaFile) {
            val importList = psiFile.importList
            if (importList != null) {
                val importStatements = importList.importStatements
                for (importStatement in importStatements) {
                    val importedClassName = importStatement.qualifiedName
                    if (importedClassName?.substringAfterLast('.') == className) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun isImportStatementContext(element: PsiElement): Boolean {
        return element is PsiImportStatement || element.parent is PsiImportList || element.parent is PsiImportStaticStatement || element.parent is PsiImportStaticReferenceElement || element.parent is PsiImportStatement
    }

    private fun formatCode(psiFile: PsiFile) {
        val codeStyleManager = CodeStyleManager.getInstance(environment.project)
        codeStyleManager.reformat(psiFile)
    }

    private fun findElementAtOffset(file: PsiFile, offset: Int): PsiElement? {
        var element = file.findElementAt(offset)
        if (element is PsiWhiteSpace || element is PsiComment) {
            element = file.findElementAt(offset - 1)
        }
        if (element is PsiJavaToken && element.tokenType == JavaTokenType.DOT) {
            element = element.getParent()
        }
        return element
    }

    @Suppress("UnstableApiUsage", "DEPRECATION")
    private fun registerExtensions() {
        val extensionArea = environment.project.extensionArea
        extensionArea.registerExtensionPoint(
            "com.intellij.virtualFileManagerListener",
            VirtualFileManagerImpl::class.java.name,
            ExtensionPoint.Kind.INTERFACE
        )
        Extensions.getRootArea().registerExtensionPoint(
            "com.intellij.treeCopyHandler",
            TreeCopyHandler::class.java.name,
            ExtensionPoint.Kind.INTERFACE
        )
        Extensions.getRootArea().registerExtensionPoint(
            "com.intellij.lang.psiAugmentProvider",
            PsiAugmentProvider::class.java.name,
            ExtensionPoint.Kind.INTERFACE
        )
        extensionArea.registerExtensionPoint(
            "com.intellij.java.elementFinder",
            PsiElementFinder::class.java.name,
            ExtensionPoint.Kind.INTERFACE
        )
        Extensions.getRootArea().registerExtensionPoint(
            "com.intellij.java.elementFinder",
            PsiElementFinder::class.java.name,
            ExtensionPoint.Kind.INTERFACE
        )
    }

    private fun getFullyQualifiedPackageName(element: PsiElement): String? {
        if (element is PsiImportStatement) {
            val importReference = element.importReference
            if (importReference != null) {
                importReference.qualifiedName?.let {
                    println("Imported package: $it")
                    return it
                }
            }
        }
        println("Not an import statement")
        return null
    }

    fun addImport(psiFile: PsiFile, importStatement: String): String {
        val typeSolver = CombinedTypeSolver()
        val config = ParserConfiguration().setSymbolResolver(JavaSymbolSolver(typeSolver))
        val parser = JavaParser(config)
        val parsed = parser.parse(psiFile.text)
        if (parsed.result.isPresent) {
            val cu = parsed.result.get()
            val imports = cu.imports
            imports.add(ImportDeclaration(importStatement, false, false))
            cu.setImports(imports)
            val printerConfiguration = DefaultPrinterConfiguration()

            return cu.toString(printerConfiguration)
        } else {
            println("Failed to parse")
            println(parsed.problems)
        }
        return psiFile.text

    }


    companion object {
        val javaKeywords = arrayOf(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null"
        )
    }
}