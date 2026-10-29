package expo.modules.guardiannativeprotection

internal object AdaptivePolicyTest {
    @JvmStatic fun main(args: Array<String>) {
        var count=0
        for(clean in listOf(false,true)) for(coherent in listOf(false,true))
            for(fire in listOf(false,true)) for(fsmn in listOf(false,true)) {
                val allowed=GuardianAdaptiveAdmissionPolicy.useFiltered(clean,coherent,fire,fsmn,false,null,0)
                check(allowed == (clean && coherent && fire && fsmn));count++
            }
        check(!GuardianAdaptiveAdmissionPolicy.useFiltered(true,true,true,true,true,1,0));count++
        check(GuardianAdaptiveAdmissionPolicy.useFiltered(true,true,true,true,true,0,0));count++
        check(GuardianAsrWindowPolicy.contextEnd(0,100_800)==128_000L);count++
        check(GuardianAsrWindowPolicy.contextEnd(0,97_600)==128_000L);count++
        check(GuardianAsrWindowPolicy.contextEnd(10_000,100_000)==132_000L);count++
        check(GuardianAsrWindowPolicy.contextEnd(0,128_000)==null);count++
        check(GuardianAsrWindowPolicy.contextEnd(0,128_001)==null);count++
        check(GuardianAsrWindowPolicy.contextEnd(-1,1)==null);count++
        check(GuardianAsrWindowPolicy.contextEnd(9,9)==null);count++
        println("$count adaptive admission/window policy cases passed")
    }
}
