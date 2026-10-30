package lightning.dsp.volumemanager.backend;
/** Completion only; no privileged operations are exposed to the service. */
oneway interface IVolumePlaybackOwner {
    void stopped();
}
