// !LANGUAGE: +AllowContractsForCustomFunctions +UseReturnsEffect
// !USE_EXPERIMENTAL: kotlin.contracts.ExperimentalContracts
// !DIAGNOSTICS: -INVISIBLE_REFERENCE -INVISIBLE_MEMBER
// !WITH_NEW_INFERENCE

import kotlin.contracts.*

fun Any?.isNull(): Boolean {
    contract {
        returns(false) implies (this@isNull != null)
    }
    return this == null
}

fun smartcastOnReceiver(x: Int?) {
    with(x) {
        if (isNull()) {
            <!OI;UNSAFE_CALL!>inc<!>()
        }
        else {
            <!OI;DEBUG_INFO_IMPLICIT_RECEIVER_SMARTCAST!>dec<!>()
        }
    }
}