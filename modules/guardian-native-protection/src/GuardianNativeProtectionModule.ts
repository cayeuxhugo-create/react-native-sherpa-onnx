import { requireNativeModule } from 'expo-modules-core';

export type GuardianVoicePhase =
  | 'STOPPED'
  | 'STARTING'
  | 'LISTENING'
  | 'WAKE_DETECTED'
  | 'EMERGENCY_DETECTED'
  | 'RESTARTING'
  | 'ERROR'
  | string;

export type GuardianProtectionStatus = {

  statusReadAt: number;

nativeCoreVersion: string;

serviceStatusAvailable: boolean;

locationStatusAvailable: boolean;
locationDataAvailable: boolean;

ptiStatusAvailable: boolean;

voiceStatusAvailable: boolean;
  serviceRunning: boolean;

  locationActive: boolean;
  ptiActive: boolean | null;
  voiceActive: boolean | null;

  voiceListening: boolean | null;
  voicePhase: GuardianVoicePhase | null;

  voiceRecognizerAvailable: boolean | null;
voiceMicrophonePermission: boolean | null;
voiceWakePhraseArmed: boolean | null;

  voiceConfidence: number | null;
  voiceWakePhrase: string | null;
  voiceEmergencyPhrase: string | null;
  voiceRestartCount: number | null;

    // ============================
  // Diagnostic Audio Guardian
  // ============================

  voiceAudioDiagnosticsAvailable: boolean;

  voiceAudioState: string | null;
  voiceAudioRms: number | null;
  voiceAudioNoiseFloor: number | null;
  voiceAudioSnrDb: number | null;
  voiceAudioSpeechLikely: boolean | null;
  voiceAudioGainEligible: boolean | null;
  voiceAudioAdaptiveGain: number | null;
  voiceAudioPeak: number | null;
  voiceAudioClippedSampleCount: number | null;
  voiceAudioDroppedFrameCount: number | null;

    // ============================
  // Capacités audio du téléphone
  // ============================

  voicePhoneAudioCapabilitiesAvailable: boolean;

  voicePhoneMicrophoneCount: number | null;
  voicePhoneBuiltInMicrophoneCount: number | null;
  voicePhoneMultiMicCandidate: boolean | null;

  voicePhoneDirectionApiAvailable: boolean | null;
  voicePhoneFieldApiAvailable: boolean | null;

  voicePhoneNoiseSuppressorAvailable: boolean | null;
  voicePhoneAgcAvailable: boolean | null;
  voicePhoneUnprocessedAvailable: boolean | null;

  voicePhoneRecommendedAudioSource: string | null;
  voicePhonePerformanceProfile: string | null;
  voicePhoneNeuralEnhancementAllowed: boolean | null;

  // ============================
  // Audio réellement utilisé par Guardian
  // ============================

  voiceGuardianAudioRuntimeAvailable: boolean;

  voiceGuardianAudioSource: string | null;
  voiceGuardianSampleRateHz: number | null;
  voiceGuardianChannelCount: number | null;

  voiceGuardianActiveMicrophoneCount: number | null;
  voiceGuardianActiveMicrophoneDescriptions: string[] | null;
  voiceGuardianChannelMappings: string[] | null;

  voiceGuardianNoiseSuppressorActive: boolean | null;
  voiceGuardianAgcActive: boolean | null;

  voiceGuardianDirectionRequested: boolean | null;
  voiceGuardianDirectionApplied: boolean | null;

  voiceGuardianFieldRequested: boolean | null;
  voiceGuardianFieldApplied: boolean | null;

  voiceGuardianMultiChannelActive: boolean | null;

  // ============================
  // Diagnostic Sherpa / Silero
  // ============================

  voiceSherpaDiagnosticsAvailable: boolean;

  voiceSherpaInitialized: boolean | null;
  voiceVadEnabled: boolean | null;
  voiceVadSpeechActive: boolean | null;
  voiceUtteranceHasAudio: boolean | null;

  voiceVadThreshold: number | null;
  voiceVadMinSpeechDuration: number | null;
  voiceVadMinSilenceDuration: number | null;
  voiceVadPreRollFrames: number | null;

  voiceSherpaLastPartialText: string | null;
  voiceSherpaLastFinalText: string | null;
  voiceSherpaLastConfidence: number | null;
  voiceSherpaFinalizedUtteranceCount: number | null;

    // ============================
  // Diagnostic Conversation
  // ============================

  voiceConversationAvailable: boolean;

  voiceConversationFinalText: string | null;
  voiceConversationSegmentCount: number | null;
  voiceConversationLastFinalizedAt: number | null;

  // ============================
  // Diagnostic Understanding
  // ============================

  voiceUnderstandingAvailable: boolean;

  voiceUnderstandingText: string | null;
  voiceUnderstandingPrimaryIntent: string | null;
  voiceUnderstandingScore: number | null;
  voiceUnderstandingEmergency: boolean | null;
  voiceUnderstandingWake: boolean | null;
  voiceUnderstandingReassurance: boolean | null;
  voiceUnderstandingEvidence: string[] | null;

  // ============================
  // Diagnostic Fusion
  // ============================

  voiceFusionAvailable: boolean;

  voiceFusionLevel: string | null;
  voiceFusionScore: number | null;
  voiceFusionEmergency: boolean | null;
  voiceFusionWake: boolean | null;
  voiceFusionStrongestIntent: string | null;
  voiceFusionSupportingIntentCount: number | null;
  voiceFusionReasons: string[] | null;

  ptiSensorAvailable: boolean | null;
ptiPhase: string | null;
ptiCurrentForceG: number | null;
ptiPeakImpactG: number | null;
ptiHorizontal: boolean | null;
ptiStillDurationSeconds: number | null;
ptiLastScore: number | null;

  startedAt: number | null;

  lastLocationAt: number | null;
  lastLatitude: number | null;
  lastLongitude: number | null;
  lastAccuracy: number | null;
  lastSpeedMps: number | null;

  lastSensorAt: number | null;
  lastAudioAt: number | null;

  pendingLocationEventCount: number;
  lastError: string | null;
};

export type GuardianNativeLocationEvent = {
  eventId: string;

  eventType: 'LOCATION';

  priority:
    | 'normal'
    | 'high'
    | 'critical';

  createdAt: number;
  recordedAt: number;

  schemaVersion: number;

  childId: string;
  familyId: string;
  familyCode: string;
  deviceId: string;

  latitude: number;
  longitude: number;

  accuracy: number | null;
  speedMps: number | null;
  altitude: number | null;
};

export type GuardianNativeConfig = {
  childId: string;
  childName: string;
  familyId: string;
  familyCode: string;
  deviceId: string;

  gpsEnabled: boolean;
  voiceEnabled: boolean;
  /** Applied on the next stop/start of protection. Default: DISTANT. */
  voiceProfile?: 'DISTANT' | 'NOISY';
  /** Logs transcriptions during controlled tests. No audio recording. */
  voiceDiagnosticsEnabled?: boolean;
  ptiEnabled: boolean;

  uploadIntervalSeconds: number;

  version?: number;
  lastUpdated?: number;
};

export type GuardianManualSosResult = {
  accepted: boolean;
  requestedAt: number;
  source: string;
};

export type GuardianNativeProtectionModuleType = {
  configureProtection(
    config: Omit<
      GuardianNativeConfig,
      'version' | 'lastUpdated'
    >
  ): Promise<boolean>;

  getNativeConfig():
    Promise<GuardianNativeConfig | null>;

  clearNativeConfig():
    Promise<boolean>;

  startProtection():
    Promise<boolean>;

  stopProtection(): boolean;

  triggerManualSos():
    Promise<GuardianManualSosResult>;

  getNativeCoreVersion(): string;

  getNativeFirebaseUid():
    Promise<string>;

  getProtectionStatus():
    Promise<GuardianProtectionStatus>;

  getPendingLocationEvents(
    limit: number
  ): Promise<GuardianNativeLocationEvent[]>;

  markEventsUploaded(
    eventIds: string[]
  ): Promise<boolean>;

  triggerUpload():
    Promise<boolean>;
};

const GuardianNativeProtection =
  requireNativeModule<GuardianNativeProtectionModuleType>(
    'GuardianNativeProtection'
  );

export default GuardianNativeProtection;