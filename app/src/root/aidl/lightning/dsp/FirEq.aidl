package lightning.dsp;

parcelable FirEq {
    float[15] frequencyHz;
    float[15] gainDb;
    int interpolation = 0;
    boolean enabled = true;
}
