/*
 * HAL GNSS AIDL de la E-PAD: envuelve el HAL heredado de fábrica (gps.mt6797.so, Android 7, interfaz GpsInterface
 * de hardware/gps.h), que a su vez habla con mnld por sockets. Solo reenvía lo que da el receptor (posición,
 * satélites, NMEA); las extensiones (AGNSS, medidas, geocercas…) se declaran no soportadas. Sin datos simulados.
 */
#define LOG_TAG "gnss-epad"

#include <aidl/android/hardware/gnss/BnGnss.h>
#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <dlfcn.h>
#include <hardware/gps.h>
#include <log/log.h>
#include <pthread.h>
#include <stdio.h>
#include <unistd.h>
#include <utils/SystemClock.h>

#include <cmath>
#include <mutex>

using aidl::android::hardware::gnss::BnGnss;
using aidl::android::hardware::gnss::ElapsedRealtime;
// gps.h también define GnssConstellationType (typedef de uint8_t)
using Constellation = aidl::android::hardware::gnss::GnssConstellationType;
using aidl::android::hardware::gnss::GnssLocation;
using aidl::android::hardware::gnss::IAGnss;
using aidl::android::hardware::gnss::IAGnssRil;
using aidl::android::hardware::gnss::IGnssAntennaInfo;
using aidl::android::hardware::gnss::IGnssBatching;
using aidl::android::hardware::gnss::IGnssCallback;
using aidl::android::hardware::gnss::IGnssConfiguration;
using aidl::android::hardware::gnss::IGnssDebug;
using aidl::android::hardware::gnss::IGnssGeofence;
using aidl::android::hardware::gnss::IGnssMeasurementInterface;
using aidl::android::hardware::gnss::IGnssNavigationMessageInterface;
using aidl::android::hardware::gnss::IGnssPowerIndication;
using aidl::android::hardware::gnss::IGnssPsds;
using aidl::android::hardware::gnss::gnss_assistance::IGnssAssistanceInterface;
using aidl::android::hardware::gnss::measurement_corrections::IMeasurementCorrectionsInterface;
using aidl::android::hardware::gnss::visibility_control::IGnssVisibilityControl;
using ndk::ScopedAStatus;

namespace {

const char* kLegacyHal = "/vendor/lib64/hw/gps.mt6797.so";
// --prueba: sin binder, imprime lo que llega del HAL de fábrica
bool gTest;

// Sin AGNSS ni medidas/navegación/geocercas: esas capacidades no se anuncian aunque el HAL de fábrica las tenga
constexpr uint32_t kCapsMask =
        GPS_CAPABILITY_SCHEDULING | GPS_CAPABILITY_SINGLE_SHOT | GPS_CAPABILITY_ON_DEMAND_TIME;

std::mutex gLock;
std::shared_ptr<IGnssCallback> gCb;
bool gSv = true, gNmea = true;
uint32_t gCaps = GPS_CAPABILITY_SCHEDULING;
const GpsInterface* gGps;

std::shared_ptr<IGnssCallback> cb() {
    std::lock_guard<std::mutex> l(gLock);
    return gCb;
}

ScopedAStatus unsupported() {
    return ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
}

ScopedAStatus result(int ret) {
    return ret == 0 ? ScopedAStatus::ok()
                    : ScopedAStatus::fromServiceSpecificError(aidl::android::hardware::gnss::IGnss::ERROR_GENERIC);
}

// --- llamadas del HAL de fábrica (desde su hilo) ---

void locationCb(GpsLocation* in) {
    if (gTest && in)
        printf("posición %.6f %.6f alt %.0f prec %.0f m\n", in->latitude, in->longitude, in->altitude, in->accuracy);
    auto c = cb();
    if (!c || !in) return;
    GnssLocation l;
    // mismas marcas en ambas interfaces para lat/long, altitud, velocidad, rumbo y precisión horizontal
    l.gnssLocationFlags = in->flags & 0x1f;
    l.latitudeDegrees = in->latitude;
    l.longitudeDegrees = in->longitude;
    l.altitudeMeters = in->altitude;
    l.speedMetersPerSec = in->speed;
    l.bearingDegrees = in->bearing;
    l.horizontalAccuracyMeters = in->accuracy;
    l.timestampMillis = in->timestamp;
    l.elapsedRealtime.flags = ElapsedRealtime::HAS_TIMESTAMP_NS | ElapsedRealtime::HAS_TIME_UNCERTAINTY_NS;
    l.elapsedRealtime.timestampNs = android::elapsedRealtimeNano();
    l.elapsedRealtime.timeUncertaintyNs = 1000000;  // llega por socket desde mnld: ~1 ms
    c->gnssLocationCb(l);
}

void statusCb(GpsStatus* s) {
    if (gTest && s) printf("estado %d\n", s->status);
    auto c = cb();
    if (c && s) c->gnssStatusCb(static_cast<IGnssCallback::GnssStatusValue>(s->status));
}

// el HAL de fábrica no llama a los avisos de satélites (al menos sin fijo): se sacan del NMEA mientras no lo haga
bool gHalSv;

void svStatusCb(GpsSvStatus* s) {  // interfaz antigua: solo GPS/SBAS/GLONASS… por número PRN
    if (gTest && s) printf("satélites (antiguo): %d\n", s->num_svs);
    gHalSv = true;
    auto c = cb();
    if (!c || !s || !gSv) return;
    std::vector<IGnssCallback::GnssSvInfo> v;
    for (int i = 0; i < s->num_svs && i < GPS_MAX_SVS; i++) {
        const GpsSvInfo& g = s->sv_list[i];
        IGnssCallback::GnssSvInfo o;
        int prn = g.prn;
        o.constellation = prn <= 32 ? Constellation::GPS
                          : prn <= 64 ? Constellation::SBAS
                          : prn <= 96 ? Constellation::GLONASS
                          : prn >= 193 && prn <= 200 ? Constellation::QZSS
                          : prn >= 201 && prn <= 235 ? Constellation::BEIDOU
                                                     : Constellation::UNKNOWN;
        o.svid = prn <= 32 ? prn : prn <= 64 ? prn + 87 : prn <= 96 ? prn - 64 : prn >= 201 ? prn - 200 : prn;
        o.cN0Dbhz = g.snr;
        o.elevationDegrees = g.elevation;
        o.azimuthDegrees = g.azimuth;
        int bit = prn >= 1 && prn <= 32 ? 1 << (prn - 1) : 0;
        o.svFlag = (s->ephemeris_mask & bit ? (int)IGnssCallback::GnssSvFlags::HAS_EPHEMERIS_DATA : 0) |
                   (s->almanac_mask & bit ? (int)IGnssCallback::GnssSvFlags::HAS_ALMANAC_DATA : 0) |
                   (s->used_in_fix_mask & bit ? (int)IGnssCallback::GnssSvFlags::USED_IN_FIX : 0);
        v.push_back(o);
    }
    c->gnssSvStatusCb(v);
}

void gnssSvStatusCb(GnssSvStatus* s) {
    if (gTest && s) {
        printf("satélites: %d", s->num_svs);
        for (int i = 0; i < s->num_svs && i < GNSS_MAX_SVS; i++)
            printf(" %d/%d:%.0f", s->gnss_sv_list[i].constellation, s->gnss_sv_list[i].svid,
                   s->gnss_sv_list[i].c_n0_dbhz);
        printf("\n");
    }
    gHalSv = true;
    auto c = cb();
    if (!c || !s || !gSv) return;
    std::vector<IGnssCallback::GnssSvInfo> v;
    for (int i = 0; i < s->num_svs && i < GNSS_MAX_SVS; i++) {
        const GnssSvInfo& g = s->gnss_sv_list[i];
        IGnssCallback::GnssSvInfo o;
        o.svid = g.svid;
        o.constellation = static_cast<Constellation>(g.constellation);  // mismos valores
        o.cN0Dbhz = g.c_n0_dbhz;
        o.elevationDegrees = g.elevation;
        o.azimuthDegrees = g.azimuth;
        o.svFlag = g.flags & 0x7;  // efemérides, almanaque, usado en el fijo: mismos bits
        v.push_back(o);
    }
    c->gnssSvStatusCb(v);
}

// --- satélites a partir del NMEA de mnld: GSV (posición y señal) + GSA (usados en el fijo); se envían al
// llegar la RMC, que cierra cada época (orden de mnld: GGA, GSA…, GSV…, RMC, VTG)

std::vector<IGnssCallback::GnssSvInfo> gNmeaSv;
std::vector<std::pair<Constellation, int>> gNmeaUsed;

std::vector<std::string> fields(const std::string& line) {
    std::vector<std::string> f;
    std::string cur;
    for (char ch : line) {
        if (ch == '*' || ch == '\r' || ch == '\n') break;
        if (ch == ',') {
            f.push_back(cur);
            cur.clear();
        } else {
            cur += ch;
        }
    }
    f.push_back(cur);
    return f;
}

// talker NMEA + número de satélite → constelación y svid de Android
bool svOf(const std::string& talker, int n, Constellation* k, int* svid) {
    if (n <= 0) return false;
    if (talker == "GP") {
        if (n <= 32) *k = Constellation::GPS, *svid = n;
        else if (n <= 64) *k = Constellation::SBAS, *svid = n + 87;
        else if (n >= 193 && n <= 200) *k = Constellation::QZSS, *svid = n;
        else return false;
    } else if (talker == "GL") {
        *k = Constellation::GLONASS, *svid = n > 64 ? n - 64 : n;
    } else if (talker == "BD" || talker == "GB") {
        *k = Constellation::BEIDOU, *svid = n > 200 ? n - 200 : n;
    } else if (talker == "GA") {
        *k = Constellation::GALILEO, *svid = n;
    } else if (talker == "GQ" || talker == "QZ") {
        *k = Constellation::QZSS, *svid = n < 193 ? n + 192 : n;
    } else {
        return false;
    }
    return true;
}

void nmeaSv(const char* nmea, int len) {
    std::string line(nmea, len > 0 ? len : strlen(nmea));
    if (line.size() < 6 || line[0] != '$') return;
    std::string talker = line.substr(1, 2), type = line.substr(3, 3);
    auto f = fields(line);
    Constellation k;
    int svid;
    if (type == "GSV") {
        for (size_t i = 4; i + 3 < f.size(); i += 4) {
            if (!svOf(talker, atoi(f[i].c_str()), &k, &svid)) continue;
            IGnssCallback::GnssSvInfo o;
            o.svid = svid;
            o.constellation = k;
            o.cN0Dbhz = f[i + 3].empty() ? 0 : atof(f[i + 3].c_str());
            o.basebandCN0DbHz = o.cN0Dbhz;
            o.elevationDegrees = f[i + 1].empty() ? NAN : atof(f[i + 1].c_str());
            o.azimuthDegrees = f[i + 2].empty() ? NAN : atof(f[i + 2].c_str());
            o.svFlag = 0;
            gNmeaSv.push_back(o);
        }
    } else if (type == "GSA") {
        for (size_t i = 3; i < 15 && i < f.size(); i++)
            if (svOf(talker, atoi(f[i].c_str()), &k, &svid)) gNmeaUsed.push_back({k, svid});
    } else if (type == "RMC") {
        for (auto& o : gNmeaSv)
            for (auto& u : gNmeaUsed)
                if (u.first == o.constellation && u.second == o.svid)
                    o.svFlag |= (int)IGnssCallback::GnssSvFlags::USED_IN_FIX;
        if (gTest && !gNmeaSv.empty()) {
            printf("satélites (NMEA): %zu", gNmeaSv.size());
            for (auto& o : gNmeaSv) printf(" %d/%d:%.0f", (int)o.constellation, o.svid, o.cN0Dbhz);
            printf("\n");
        }
        auto c = cb();
        if (c && gSv) c->gnssSvStatusCb(gNmeaSv);
        gNmeaSv.clear();
        gNmeaUsed.clear();
    }
}

void nmeaCb(GpsUtcTime t, const char* nmea, int len) {
    if (gTest && nmea) printf("%.*s", len, nmea);
    if (nmea && !gHalSv) nmeaSv(nmea, len);
    auto c = cb();
    if (c && nmea && gNmea) c->gnssNmeaCb(t, std::string(nmea, len > 0 ? len : strlen(nmea)));
}

void capabilitiesCb(uint32_t caps) {
    ALOGI("capacidades del HAL de fábrica 0x%x", caps);
    if (gTest) printf("capacidades 0x%x\n", caps);
    gCaps = caps & kCapsMask;
    if (auto c = cb()) c->gnssSetCapabilitiesCb(gCaps);
}

void acquireWakelockCb() {
    if (auto c = cb()) c->gnssAcquireWakelockCb();
}

void releaseWakelockCb() {
    if (auto c = cb()) c->gnssReleaseWakelockCb();
}

void requestUtcTimeCb() {
    if (auto c = cb()) c->gnssRequestTimeCb();
}

void systemInfoCb(const GnssSystemInfo* info) {
    auto c = cb();
    if (!c || !info) return;
    IGnssCallback::GnssSystemInfo i;
    i.yearOfHw = info->year_of_hw;
    i.name = "MT6797 ConSys";
    c->gnssSetSystemInfoCb(i);
}

struct Thread {
    void (*start)(void*);
    void* arg;
};

void* threadMain(void* p) {
    Thread t = *static_cast<Thread*>(p);
    delete static_cast<Thread*>(p);
    t.start(t.arg);
    return nullptr;
}

pthread_t createThreadCb(const char* name, void (*start)(void*), void* arg) {
    pthread_t th = 0;
    if (pthread_create(&th, nullptr, threadMain, new Thread{start, arg}) == 0 && name)
        pthread_setname_np(th, name);
    return th;
}

GpsCallbacks gCallbacks = {
        .size = sizeof(GpsCallbacks),
        .location_cb = locationCb,
        .status_cb = statusCb,
        .sv_status_cb = svStatusCb,
        .nmea_cb = nmeaCb,
        .set_capabilities_cb = capabilitiesCb,
        .acquire_wakelock_cb = acquireWakelockCb,
        .release_wakelock_cb = releaseWakelockCb,
        .create_thread_cb = createThreadCb,
        .request_utc_time_cb = requestUtcTimeCb,
        .set_system_info_cb = systemInfoCb,
        .gnss_sv_status_cb = gnssSvStatusCb,
};

const GpsInterface* loadLegacy() {
    void* h = dlopen(kLegacyHal, RTLD_NOW);
    if (!h) {
        ALOGE("dlopen %s: %s", kLegacyHal, dlerror());
        return nullptr;
    }
    auto* module = static_cast<hw_module_t*>(dlsym(h, HAL_MODULE_INFO_SYM_AS_STR));
    hw_device_t* dev = nullptr;
    if (!module || module->methods->open(module, GPS_HARDWARE_MODULE_ID, &dev) != 0 || !dev) {
        ALOGE("no se pudo abrir el módulo %s", kLegacyHal);
        return nullptr;
    }
    return reinterpret_cast<gps_device_t*>(dev)->get_gps_interface(reinterpret_cast<gps_device_t*>(dev));
}

class Gnss : public BnGnss {
  public:
    ScopedAStatus setCallback(const std::shared_ptr<IGnssCallback>& c) override {
        {
            std::lock_guard<std::mutex> l(gLock);
            gCb = c;
        }
        if (!gGps) {
            gGps = loadLegacy();
            if (!gGps || gGps->init(&gCallbacks) != 0) {
                ALOGE("init del HAL de fábrica falló");
                gGps = nullptr;
                return ScopedAStatus::fromServiceSpecificError(aidl::android::hardware::gnss::IGnss::ERROR_GENERIC);
            }
        }
        c->gnssSetCapabilitiesCb(gCaps);
        IGnssCallback::GnssSystemInfo i;
        i.yearOfHw = 2016;
        i.name = "MT6797 ConSys (mnld de fábrica)";
        c->gnssSetSystemInfoCb(i);
        return ScopedAStatus::ok();
    }

    ScopedAStatus close() override {
        if (gGps) gGps->cleanup();
        gGps = nullptr;
        std::lock_guard<std::mutex> l(gLock);
        gCb = nullptr;
        return ScopedAStatus::ok();
    }

    ScopedAStatus start() override { return gGps ? result(gGps->start()) : unsupported(); }
    ScopedAStatus stop() override { return gGps ? result(gGps->stop()) : unsupported(); }

    ScopedAStatus injectTime(int64_t timeMs, int64_t timeReferenceMs, int uncertaintyMs) override {
        return gGps ? result(gGps->inject_time(timeMs, timeReferenceMs, uncertaintyMs)) : unsupported();
    }

    ScopedAStatus injectLocation(const GnssLocation& l) override {
        return gGps ? result(gGps->inject_location(l.latitudeDegrees, l.longitudeDegrees,
                                                   l.horizontalAccuracyMeters))
                    : unsupported();
    }

    ScopedAStatus injectBestLocation(const GnssLocation& l) override { return injectLocation(l); }

    ScopedAStatus deleteAidingData(GnssAidingData flags) override {
        if (!gGps) return unsupported();
        gGps->delete_aiding_data(static_cast<GpsAidingData>(flags));
        return ScopedAStatus::ok();
    }

    ScopedAStatus setPositionMode(const PositionModeOptions& o) override {
        if (!gGps) return unsupported();
        // sin AGNSS (no hay módem): siempre autónomo
        return result(gGps->set_position_mode(GPS_POSITION_MODE_STANDALONE,
                                              static_cast<GpsPositionRecurrence>(o.recurrence),
                                              o.minIntervalMs, o.preferredAccuracyMeters, o.preferredTimeMs));
    }

    ScopedAStatus startSvStatus() override { gSv = true; return ScopedAStatus::ok(); }
    ScopedAStatus stopSvStatus() override { gSv = false; return ScopedAStatus::ok(); }
    ScopedAStatus startNmea() override { gNmea = true; return ScopedAStatus::ok(); }
    ScopedAStatus stopNmea() override { gNmea = false; return ScopedAStatus::ok(); }

    ScopedAStatus getExtensionPsds(std::shared_ptr<IGnssPsds>*) override { return unsupported(); }
    ScopedAStatus getExtensionGnssConfiguration(std::shared_ptr<IGnssConfiguration>*) override {
        return unsupported();
    }
    ScopedAStatus getExtensionGnssMeasurement(std::shared_ptr<IGnssMeasurementInterface>*) override {
        return unsupported();
    }
    ScopedAStatus getExtensionGnssPowerIndication(std::shared_ptr<IGnssPowerIndication>*) override {
        return unsupported();
    }
    ScopedAStatus getExtensionGnssBatching(std::shared_ptr<IGnssBatching>*) override { return unsupported(); }
    ScopedAStatus getExtensionGnssGeofence(std::shared_ptr<IGnssGeofence>*) override { return unsupported(); }
    ScopedAStatus getExtensionGnssNavigationMessage(std::shared_ptr<IGnssNavigationMessageInterface>*) override {
        return unsupported();
    }
    ScopedAStatus getExtensionAGnss(std::shared_ptr<IAGnss>*) override { return unsupported(); }
    ScopedAStatus getExtensionAGnssRil(std::shared_ptr<IAGnssRil>*) override { return unsupported(); }
    ScopedAStatus getExtensionGnssDebug(std::shared_ptr<IGnssDebug>*) override { return unsupported(); }
    ScopedAStatus getExtensionGnssVisibilityControl(std::shared_ptr<IGnssVisibilityControl>*) override {
        return unsupported();
    }
    ScopedAStatus getExtensionGnssAntennaInfo(std::shared_ptr<IGnssAntennaInfo>*) override {
        return unsupported();
    }
    ScopedAStatus getExtensionMeasurementCorrections(std::shared_ptr<IMeasurementCorrectionsInterface>*) override {
        return unsupported();
    }
    ScopedAStatus getExtensionGnssAssistanceInterface(std::shared_ptr<IGnssAssistanceInterface>*) override {
        return unsupported();
    }
};

}  // namespace

// Prueba sin framework: android.hardware.gnss-service.epad --prueba [SEGUNDOS] [HAL.so]
int test(int secs) {
    gTest = true;
    setvbuf(stdout, nullptr, _IOLBF, 0);
    gGps = loadLegacy();
    if (!gGps || gGps->init(&gCallbacks) != 0) {
        printf("init falló\n");
        return 1;
    }
    printf("set_position_mode %d\n", gGps->set_position_mode(GPS_POSITION_MODE_STANDALONE,
                                                             GPS_POSITION_RECURRENCE_PERIODIC, 1000, 0, 0));
    printf("start %d\n", gGps->start());
    sleep(secs);
    printf("stop %d\n", gGps->stop());
    sleep(2);
    gGps->cleanup();
    return 0;
}

int main(int argc, char** argv) {
    if (argc > 1 && !strcmp(argv[1], "--prueba")) {
        if (argc > 3) kLegacyHal = argv[3];
        return test(argc > 2 ? atoi(argv[2]) : 60);
    }
    ABinderProcess_setThreadPoolMaxThreadCount(1);
    ABinderProcess_startThreadPool();
    auto gnss = ndk::SharedRefBase::make<Gnss>();
    const std::string name = std::string(Gnss::descriptor) + "/default";
    CHECK_EQ(AServiceManager_addService(gnss->asBinder().get(), name.c_str()), STATUS_OK);
    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}
