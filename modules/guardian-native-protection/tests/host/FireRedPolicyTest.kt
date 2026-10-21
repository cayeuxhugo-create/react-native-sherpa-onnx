package expo.modules.guardiannativeprotection

fun fireRedPolicyTests() {
    check(!GuardianFireRedPolicy.evaluate(floatArrayOf()).accepted)
    check(!GuardianFireRedPolicy.evaluate(FloatArray(800)).accepted)
    check(!GuardianFireRedPolicy.evaluate(FloatArray(20) { 1f }).accepted)
    check(GuardianFireRedPolicy.evaluate(FloatArray(21) { 1f }).accepted)
    check(!GuardianFireRedPolicy.evaluate(FloatArray(800) { if (it % 10 < 3) 1f else 0f }).accepted)
    check(GuardianFireRedPolicy.evaluate(FloatArray(800) { .5f }).accepted)
    check(!GuardianFireRedPolicy.evaluate(FloatArray(800) { .499f }).accepted)
    check(runCatching { GuardianFireRedPolicy.evaluate(floatArrayOf(Float.NaN)) }.isFailure)
    check(runCatching { GuardianFireRedPolicy.evaluate(floatArrayOf(1.01f)) }.isFailure)
    println("9 FireRed admission policy checks passed")
}
