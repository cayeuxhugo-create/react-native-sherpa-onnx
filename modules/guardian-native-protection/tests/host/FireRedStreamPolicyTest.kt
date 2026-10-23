package expo.modules.guardiannativeprotection
fun fireRedStreamPolicyTests() {
    val p=GuardianFireRedStreamPolicy()
    check(!p.accept(FloatArray(100)))
    p.reset();check(!p.accept(FloatArray(7){1f}));check(p.accept(floatArrayOf(1f)))
    check(p.accept(FloatArray(19))) // smoothed end includes the five-frame history
    check(!p.accept(FloatArray(5)))
    p.reset();check(!p.accept(FloatArray(7){1f}));p.reset();check(!p.accept(floatArrayOf(1f)))
    check(runCatching { p.accept(floatArrayOf(Float.NaN)) }.isFailure)
    check(runCatching { p.accept(floatArrayOf(1.1f)) }.isFailure)
    p.reset();check(!p.accept(FloatArray(2000){if(it%10<3)1f else 0f}))
    println("PASS FireRed streaming onset, smoothed release, reset and invalid probability guards")
}
