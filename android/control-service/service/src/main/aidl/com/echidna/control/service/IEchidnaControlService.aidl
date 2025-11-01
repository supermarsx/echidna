package com.echidna.control.service;

interface IEchidnaControlService {
    void installModule(String archivePath);
    void uninstallModule();
    void refreshStatus();
    String getModuleStatus();
    void updateWhitelist(String processName, boolean enabled);
    void pushProfile(String profileId, String profileJson);
    String[] listProfiles();
}
