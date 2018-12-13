/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.contracts

import org.jetbrains.kotlin.contracts.model.ESReceiver
import org.jetbrains.kotlin.contracts.model.ESValue
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue

fun ESValue.extractReceiverValue(): ReceiverValue? = when(this) {
    is ESReceiver -> receiver
    is ESDataFlowValueWithExpressionReceiver -> receiverParameter ?: receiver
    is ESDataFlowValue -> receiverParameter
    else -> null
}

private val ESDataFlowValue.receiverParameter : ReceiverValue?
    get() = (descriptor as? ReceiverParameterDescriptor)?.value