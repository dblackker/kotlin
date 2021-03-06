/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.compiler.backend.ir

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrPropertyImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrTypeParameterImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.toKotlinType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.KotlinTypeFactory
import org.jetbrains.kotlin.types.TypeProjectionImpl
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.typeUtil.isTypeParameter
import org.jetbrains.kotlinx.serialization.compiler.backend.common.findTypeSerializerOrContext
import org.jetbrains.kotlinx.serialization.compiler.backend.jvm.contextSerializerId
import org.jetbrains.kotlinx.serialization.compiler.backend.jvm.enumSerializerId
import org.jetbrains.kotlinx.serialization.compiler.backend.jvm.referenceArraySerializerId
import org.jetbrains.kotlinx.serialization.compiler.resolve.*

val BackendContext.externalSymbols: ReferenceSymbolTable get() = ir.symbols.externalSymbolTable

internal fun BackendContext.createTypeTranslator(moduleDescriptor: ModuleDescriptor): TypeTranslator =
    TypeTranslator(externalSymbols, irBuiltIns.languageVersionSettings).apply {
        constantValueGenerator = ConstantValueGenerator(moduleDescriptor, symbolTable = externalSymbols)
        constantValueGenerator.typeTranslator = this
    }

interface IrBuilderExtension {
    val compilerContext: BackendContext
    val translator: TypeTranslator

    val BackendContext.localSymbolTable: SymbolTable

    private fun IrClass.declareSimpleFunctionWithExternalOverrides(descriptor: FunctionDescriptor): IrSimpleFunction {
        return compilerContext.localSymbolTable.declareSimpleFunction(startOffset, endOffset, SERIALIZABLE_PLUGIN_ORIGIN, descriptor).also {f ->
            descriptor.overriddenDescriptors.mapTo(f.overriddenSymbols) {
                compilerContext.externalSymbols.referenceSimpleFunction(it.original)
            }
        }
    }

    fun IrClass.contributeFunction(descriptor: FunctionDescriptor, fromStubs: Boolean = false, bodyGen: IrBlockBodyBuilder.(IrFunction) -> Unit) {
        val f: IrSimpleFunction = if (!fromStubs) declareSimpleFunctionWithExternalOverrides(
            descriptor
        ) else compilerContext.externalSymbols.referenceSimpleFunction(descriptor).owner
        f.parent = this
        f.returnType = descriptor.returnType!!.toIrType()
        if (!fromStubs) f.createParameterDeclarations(this.thisReceiver)
        f.body = compilerContext.createIrBuilder(f.symbol).irBlockBody { bodyGen(f) }
        this.addMember(f)
    }

    fun IrClass.contributeCtor(descriptor: ClassConstructorDescriptor, bodyGen: IrBlockBodyBuilder.(IrFunction) -> Unit) {
        val c = compilerContext.externalSymbols.referenceConstructor(descriptor).owner
        c.parent = this
        c.returnType = descriptor.returnType.toIrType()
        c.body = compilerContext.createIrBuilder(c.symbol).irBlockBody { bodyGen(c) }
        this.addMember(c)
    }

    fun IrClass.contributeConstructor(
        descriptor: ClassConstructorDescriptor,
        bodyGen: IrBlockBodyBuilder.(IrConstructor) -> Unit
    ) {
        val c = compilerContext.localSymbolTable.declareConstructor(
            this.startOffset,
            this.endOffset,
            SERIALIZABLE_PLUGIN_ORIGIN,
            descriptor
        )
        c.parent = this
        c.returnType = descriptor.returnType.toIrType()
        c.createParameterDeclarations()
        c.body = compilerContext.createIrBuilder(c.symbol).irBlockBody { bodyGen(c) }
        this.addMember(c)
    }

    fun IrBuilderWithScope.irInvoke(
        dispatchReceiver: IrExpression? = null,
        callee: IrFunctionSymbol,
        vararg args: IrExpression,
        typeHint: IrType? = null
    ): IrCall {
        val call = typeHint?.let { irCall(callee, type = it) } ?: irCall(callee)
        call.dispatchReceiver = dispatchReceiver
        args.forEachIndexed(call::putValueArgument)
        return call
    }

    fun IrBuilderWithScope.createArrayOfExpression(
            arrayElementType: IrType,
            arrayElements: List<IrExpression>
    ): IrExpression {

        val arrayType = compilerContext.ir.symbols.array.typeWith(arrayElementType)
        val arg0 = IrVarargImpl(startOffset, endOffset, arrayType, arrayElementType, arrayElements)
        val typeArguments = listOf(arrayElementType)

        return irCall(compilerContext.ir.symbols.arrayOf, arrayType, typeArguments = typeArguments).apply {
            putValueArgument(0, arg0)
        }
    }

    fun IrBuilderWithScope.irBinOp(name: Name, lhs: IrExpression, rhs: IrExpression): IrExpression {
        val symbol = compilerContext.ir.symbols.getBinaryOperator(
            name,
            lhs.type.toKotlinType(),
            rhs.type.toKotlinType()
        )
        return irInvoke(lhs, symbol, rhs)
    }

    fun IrBuilderWithScope.irGetObject(classDescriptor: ClassDescriptor) =
        IrGetObjectValueImpl(
            startOffset,
            endOffset,
            classDescriptor.defaultType.toIrType(),
            compilerContext.externalSymbols.referenceClass(classDescriptor)
        )

    fun IrBuilderWithScope.irGetObject(irObject: IrClass) =
        IrGetObjectValueImpl(
            startOffset,
            endOffset,
            irObject.defaultType,
            irObject.symbol
        )

    fun <T : IrDeclaration> T.buildWithScope(builder: (T) -> Unit): T =
        also { irDeclaration ->
            compilerContext.localSymbolTable.withScope(irDeclaration.descriptor) {
                builder(irDeclaration)
            }
        }

    fun IrBuilderWithScope.irEmptyVararg(forValueParameter: ValueParameterDescriptor) =
        IrVarargImpl(
            startOffset,
            endOffset,
            forValueParameter.type.toIrType(),
            forValueParameter.varargElementType!!.toIrType()
        )

    class BranchBuilder(
        val irWhen: IrWhen,
        context: IrGeneratorContext,
        scope: Scope,
        startOffset: Int,
        endOffset: Int
    ) : IrBuilderWithScope(context, scope, startOffset, endOffset) {
        operator fun IrBranch.unaryPlus() {
            irWhen.branches.add(this)
        }
    }

    fun IrBuilderWithScope.irWhen(typeHint: IrType? = null, block: BranchBuilder.() -> Unit): IrWhen {
        val whenExpr = IrWhenImpl(startOffset, endOffset, typeHint ?: compilerContext.irBuiltIns.unitType)
        val builder = BranchBuilder(whenExpr, context, scope, startOffset, endOffset)
        builder.block()
        return whenExpr
    }

    fun BranchBuilder.elseBranch(result: IrExpression): IrElseBranch =
        IrElseBranchImpl(
            IrConstImpl.boolean(result.startOffset, result.endOffset, compilerContext.irBuiltIns.booleanType, true),
            result
        )

    fun translateType(ktType: KotlinType): IrType =
        translator.translateType(ktType)

    fun KotlinType.toIrType() = translateType(this)


    val SerializableProperty.irField: IrField get() = compilerContext.externalSymbols.referenceField(this.descriptor).owner

    /*
     The rest of the file is mainly copied from FunctionGenerator.
     However, I can't use it's directly because all generateSomething methods require KtProperty (psi element)
     Also, FunctionGenerator itself has DeclarationGenerator as ctor param, which is a part of psi2ir
     (it can be instantiated here, but I don't know how good is that idea)
     */

    fun IrBuilderWithScope.generateAnySuperConstructorCall(toBuilder: IrBlockBodyBuilder) {
        val anyConstructor = compilerContext.builtIns.any.constructors.single()
        with(toBuilder) {
            +IrDelegatingConstructorCallImpl(
                startOffset, endOffset,
                compilerContext.irBuiltIns.unitType,
                compilerContext.externalSymbols.referenceConstructor(anyConstructor),
                anyConstructor
            )
        }
    }

    fun generateSimplePropertyWithBackingField(
        ownerSymbol: IrValueSymbol,
        propertyDescriptor: PropertyDescriptor,
        propertyParent: IrClass
    ): IrProperty {
        val irProperty = IrPropertyImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            SERIALIZABLE_PLUGIN_ORIGIN, false,
            propertyDescriptor
        )
        irProperty.parent = propertyParent
        irProperty.backingField = generatePropertyBackingField(propertyDescriptor).apply { parent = propertyParent }
        val fieldSymbol = irProperty.backingField!!.symbol
        irProperty.getter = propertyDescriptor.getter?.let { generatePropertyAccessor(it, fieldSymbol, ownerSymbol) }
            ?.apply { parent = propertyParent }
        irProperty.setter = propertyDescriptor.setter?.let { generatePropertyAccessor(it, fieldSymbol, ownerSymbol) }
            ?.apply { parent = propertyParent }
        return irProperty
    }

    fun generatePropertyBackingField(propertyDescriptor: PropertyDescriptor): IrField {
        return compilerContext.localSymbolTable.declareField(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            SERIALIZABLE_PLUGIN_ORIGIN,
            propertyDescriptor,
            propertyDescriptor.type.toIrType()
        )
    }

    fun generatePropertyAccessor(
        descriptor: PropertyAccessorDescriptor,
        fieldSymbol: IrFieldSymbol,
        ownerSymbol: IrValueSymbol
    ): IrSimpleFunction {
        // Declaration can also be called from user code. Since we lookup descriptor getter in externalSymbols
        // (see generateSave/generateLoad), seems it is correct approach to declare getter lazily there.
        val declaration = compilerContext.externalSymbols.referenceSimpleFunction(descriptor).owner
        return declaration.buildWithScope { irAccessor ->
            irAccessor.createParameterDeclarations((ownerSymbol as IrValueParameterSymbol).owner) // todo: neat this
            irAccessor.returnType = irAccessor.descriptor.returnType!!.toIrType()
            irAccessor.body = when (descriptor) {
                is PropertyGetterDescriptor -> generateDefaultGetterBody(descriptor, irAccessor, ownerSymbol)
                is PropertySetterDescriptor -> generateDefaultSetterBody(descriptor, irAccessor, ownerSymbol)
                else -> throw AssertionError("Should be getter or setter: $descriptor")
            }
        }
    }

    private fun generateDefaultGetterBody(
        getter: PropertyGetterDescriptor,
        irAccessor: IrSimpleFunction,
        ownerSymbol: IrValueSymbol
    ): IrBlockBody {
        val property = getter.correspondingProperty

        val irBody = IrBlockBodyImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET)

        val receiver = generateReceiverExpressionForFieldAccess(ownerSymbol, property)

        irBody.statements.add(
            IrReturnImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET, compilerContext.irBuiltIns.nothingType,
                irAccessor.symbol,
                IrGetFieldImpl(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    compilerContext.localSymbolTable.referenceField(property),
                    property.type.toIrType(),
                    receiver
                )
            )
        )
        return irBody
    }

    private fun generateDefaultSetterBody(
        setter: PropertySetterDescriptor,
        irAccessor: IrSimpleFunction,
        ownerSymbol: IrValueSymbol
    ): IrBlockBody {
        val property = setter.correspondingProperty

        val startOffset = UNDEFINED_OFFSET
        val endOffset = UNDEFINED_OFFSET
        val irBody = IrBlockBodyImpl(startOffset, endOffset)

        val receiver = generateReceiverExpressionForFieldAccess(ownerSymbol, property)

        val irValueParameter = irAccessor.valueParameters.single()
        irBody.statements.add(
            IrSetFieldImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                compilerContext.localSymbolTable.referenceField(property),
                receiver,
                IrGetValueImpl(startOffset, endOffset, irValueParameter.type, irValueParameter.symbol),
                compilerContext.irBuiltIns.unitType
            )
        )
        return irBody
    }

    fun generateReceiverExpressionForFieldAccess(
        ownerSymbol: IrValueSymbol,
        property: PropertyDescriptor
    ): IrExpression {
        val containingDeclaration = property.containingDeclaration
        return when (containingDeclaration) {
            is ClassDescriptor ->
                IrGetValueImpl(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
//                symbolTable.referenceValue(containingDeclaration.thisAsReceiverParameter)
                    ownerSymbol
                )
            else -> throw AssertionError("Property must be in class")
        }
    }

    // todo: delet zis
    fun IrFunction.createParameterDeclarations(receiver: IrValueParameter? = null) {
        fun ParameterDescriptor.irValueParameter() = IrValueParameterImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            SERIALIZABLE_PLUGIN_ORIGIN,
            this,
            type.toIrType(),
            (this as? ValueParameterDescriptor)?.varargElementType?.toIrType()
        ).also {
            it.parent = this@createParameterDeclarations
        }

        dispatchReceiverParameter = receiver ?: (descriptor.dispatchReceiverParameter?.irValueParameter())
        extensionReceiverParameter = descriptor.extensionReceiverParameter?.irValueParameter()

        assert(valueParameters.isEmpty())
        descriptor.valueParameters.mapTo(valueParameters) { it.irValueParameter() }

        assert(typeParameters.isEmpty())
        descriptor.typeParameters.mapTo(typeParameters) {
            IrTypeParameterImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                SERIALIZABLE_PLUGIN_ORIGIN,
                it
            ).also { typeParameter ->
                typeParameter.parent = this
            }
        }
    }



    fun IrBuilderWithScope.classReference(classType: KotlinType): IrClassReference {
        val clazz = classType.toClassDescriptor!!
        val kClass = clazz.module.findClassAcrossModuleDependencies(ClassId(FqName("kotlin.reflect"), Name.identifier("KClass")))!!
        val returnType = KotlinTypeFactory.simpleNotNullType(Annotations.EMPTY, kClass, listOf(TypeProjectionImpl(Variance.INVARIANT, classType)))
        return IrClassReferenceImpl(
            startOffset,
            endOffset,
            returnType.toIrType(),
            compilerContext.externalSymbols.referenceClassifier(clazz),
            classType.toIrType()
        )
    }

    fun buildInitializersRemapping(irClass: IrClass): (IrField) -> IrExpression? {
        val original = irClass.constructors.singleOrNull { it.isPrimary }
            ?: throw IllegalStateException("Serializable class must have single primary constructor")
        // default arguments of original constructor
        val defaultsMap: Map<ParameterDescriptor, IrExpression?> =
            original.valueParameters.associate { it.descriptor to it.defaultValue?.expression }
        return fun(f: IrField): IrExpression? {
            val i = f.initializer?.expression ?: return null
            return if (i is IrGetValueImpl && i.origin == IrStatementOrigin.INITIALIZE_PROPERTY_FROM_PARAMETER) {
                // this is a primary constructor property, use corresponding default of value parameter
                defaultsMap.getValue(i.descriptor as ParameterDescriptor)
            } else {
                i
            }
        }
    }

    fun findEnumValuesMethod(enumClass: ClassDescriptor): IrFunction {
        assert(enumClass.kind == ClassKind.ENUM_CLASS)
        return compilerContext.externalSymbols.referenceClass(enumClass).owner.functions
            .find { it.origin == IrDeclarationOrigin.ENUM_CLASS_SPECIAL_MEMBER && it.name == Name.identifier("values") }
            ?: throw AssertionError("Enum class does not have .values() function")
    }

    private fun getEnumMembersNames(enumClass: ClassDescriptor): Sequence<String> {
        assert(enumClass.kind == ClassKind.ENUM_CLASS)
        return enumClass.unsubstitutedMemberScope.getContributedDescriptors().asSequence()
            .filterIsInstance<ClassDescriptor>()
            .filter { it.kind == ClassKind.ENUM_ENTRY }
            .map { it.name.toString() }
    }

    // Does not use sti and therefore does not perform encoder calls optimization
    fun IrBuilderWithScope.serializerTower(generator: SerializerIrGenerator, property: SerializableProperty): IrExpression? {
        val nullableSerClass =
                compilerContext.externalSymbols.referenceClass(property.module.getClassFromInternalSerializationPackage(SpecialBuiltins.nullableSerializer))
        val serializer =
                property.serializableWith?.toClassDescriptor
                        ?: if (!property.type.isTypeParameter()) generator.findTypeSerializerOrContext(
                                property.module,
                                property.type,
                                property.descriptor.annotations,
                                property.descriptor.findPsi()
                        ) else null
        return serializerInstance(generator, generator.serializableDescriptor, serializer, property.module, property.type, property.genericIndex)
                ?.let { expr -> if (property.type.isMarkedNullable) irInvoke(null, nullableSerClass.constructors.toList()[0], expr) else expr }
    }

    fun IrBuilderWithScope.serializerInstance(
        enclosingGenerator: SerializerIrGenerator,
        serializableDescriptor: ClassDescriptor,
        serializerClassOriginal: ClassDescriptor?,
        module: ModuleDescriptor,
        kType: KotlinType,
        genericIndex: Int? = null
    ): IrExpression? {
        val nullableSerClass =
            compilerContext.externalSymbols.referenceClass(module.getClassFromInternalSerializationPackage(SpecialBuiltins.nullableSerializer))
        if (serializerClassOriginal == null) {
            if (genericIndex == null) return null
            val thiz = enclosingGenerator.irClass.thisReceiver!!
            val prop = enclosingGenerator.localSerializersFieldsDescriptors[genericIndex]
            return irGetField(irGet(thiz), compilerContext.localSymbolTable.referenceField(prop).owner)
        }
        if (serializerClassOriginal.kind == ClassKind.OBJECT) {
            return irGetObject(serializerClassOriginal)
        } else {
            var serializerClass = serializerClassOriginal
            var args: List<IrExpression> = when (serializerClassOriginal.classId) {
                contextSerializerId -> listOf(classReference(kType))
                enumSerializerId -> {
                    serializerClass = serializableDescriptor.getClassFromInternalSerializationPackage("CommonEnumSerializer")
                    kType.toClassDescriptor!!.let { enumDesc ->
                        listOf(
                            irString(enumDesc.name.toString()),
                            irCall(findEnumValuesMethod(enumDesc)),
                            createArrayOfExpression(
                                compilerContext.irBuiltIns.stringType,
                                getEnumMembersNames(enumDesc).map { irString(it) }.toList()
                            )
                        )
                    }
                }
                else -> kType.arguments.map {
                    val argSer = enclosingGenerator.findTypeSerializerOrContext(module, it.type, sourceElement = serializerClassOriginal.findPsi())
                    val expr = serializerInstance(enclosingGenerator, serializableDescriptor, argSer, module, it.type, it.type.genericIndex)
                        ?: return null
                    if (it.type.isMarkedNullable) irInvoke(null, nullableSerClass.constructors.toList()[0], expr) else expr
                }
            }
            if (serializerClassOriginal.classId == referenceArraySerializerId)
                args = listOf(classReference(kType.arguments[0].type)) + args

            val serializable = getSerializableClassDescriptorBySerializer(serializerClass)
            val ctor = if (serializable?.declaredTypeParameters?.isNotEmpty() == true) {
                requireNotNull(
                    KSerializerDescriptorResolver.findSerializerConstructorForTypeArgumentsSerializers(serializerClass)
                ) { "Generated serializer does not have constructor with required number of arguments" }
                    .let { compilerContext.externalSymbols.referenceConstructor(it) }
            } else {
                compilerContext.externalSymbols.referenceConstructor(serializerClass.unsubstitutedPrimaryConstructor!!)
            }
            return irInvoke(
                null,
                ctor,
                *args.toTypedArray()
            )
        }
    }
}
