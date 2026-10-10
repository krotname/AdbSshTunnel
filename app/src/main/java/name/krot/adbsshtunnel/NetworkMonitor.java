package name.krot.adbsshtunnel;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.telephony.*;
import androidx.core.content.ContextCompat;
import java.net.*;
import java.util.*;

/** Uses physical Internet networks, including those underneath a VPN, never IMS. */
public final class NetworkMonitor implements AutoCloseable {
    public interface Listener { void onNetworks(List<Entry> entries); }
    public static final class Entry {
        public final NetworkSettings.Kind kind;
        public final String identity, label, interfaceName;
        public final long handle;
        public final List<String> addresses;
        public final NetworkPolicy.Decision decision;
        Entry(NetworkSettings.Kind kind, String identity, String label, String iface,
              long handle, List<String> addresses, NetworkPolicy.Decision decision) {
            this.kind = kind; this.identity = identity; this.label = label;
            this.interfaceName = iface; this.handle = handle;
            this.addresses = Collections.unmodifiableList(addresses); this.decision = decision;
        }
    }
    private static final class Observed {
        NetworkCapabilities capabilities;
        LinkProperties links;
        boolean blocked = true;
    }
    private final Context context;
    private final ConnectivityManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Map<Network, Observed> observed = new HashMap<>();
    private boolean closed, registered, receiverRegistered;
    private final Map<Integer, PhoneWatch> phones = new HashMap<>();
    private SubscriptionManager subscriptions;
    private boolean subscriptionsRegistered;
    private final SubscriptionManager.OnSubscriptionsChangedListener subscriptionChanges = new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override public void onSubscriptionsChanged() { clearPhones(); emit(); }
    };
    private static final class PhoneWatch {
        final TelephonyManager manager;
        final PhoneStateListener listener;
        PhoneWatch(TelephonyManager manager, PhoneStateListener listener) { this.manager = manager; this.listener = listener; }
    }
    private ConnectivityManager.NetworkCallback callback;
    private final BroadcastReceiver location = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refresh(); }
    };
    public NetworkMonitor(Context context, Listener listener) {
        this.context = context; this.listener = listener;
        manager = context.getSystemService(ConnectivityManager.class);
    }
    private final class Callback extends ConnectivityManager.NetworkCallback {
            Callback() { super(); }
            @androidx.annotation.RequiresApi(31) Callback(int flags) { super(flags); }
            private boolean current() { return !closed && callback == this; }
            @Override public void onAvailable(Network network) {
                if (!current()) return;
                observed.put(network, new Observed()); emit();
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (!current()) return;
                observed.computeIfAbsent(network, n -> new Observed()).capabilities = caps; emit();
            }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) {
                if (!current()) return;
                observed.computeIfAbsent(network, n -> new Observed()).links = links; emit();
            }
            @Override public void onLost(Network network) {
                if (!current()) return;
                observed.remove(network); emit();
            }
            @Override public void onBlockedStatusChanged(Network network, boolean blocked) {
                if (!current()) return;
                observed.computeIfAbsent(network, n -> new Observed()).blocked = blocked; emit();
            }
    }
    public void start() {
        if (closed) return;
        try {
            ContextCompat.registerReceiver(context, location,
                new IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
            registerNetworks();
            subscriptions = context.getSystemService(SubscriptionManager.class);
            if (subscriptions != null && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                subscriptions.addOnSubscriptionsChangedListener(context.getMainExecutor(), subscriptionChanges);
                subscriptionsRegistered = true;
            }
        } catch (RuntimeException e) { close(); listener.onNetworks(Collections.emptyList()); }
    }
    private void registerNetworks() {
        callback = Build.VERSION.SDK_INT >= 31
            ? new Callback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) : new Callback();
        manager.registerNetworkCallback(new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback, main);
        registered = true;
    }
    public void refresh() {
        if (closed) return;
        ConnectivityManager.NetworkCallback previous = callback;
        callback = null;
        boolean wasRegistered = registered;
        registered = false;
        observed.clear();
        emit();
        try {
            if (wasRegistered) manager.unregisterNetworkCallback(previous);
            registerNetworks();
        } catch (RuntimeException e) { close(); listener.onNetworks(Collections.emptyList()); }
    }
    private boolean canReadWifi() {
        LocationManager locationManager = context.getSystemService(LocationManager.class);
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            && locationManager != null && locationManager.isLocationEnabled();
    }
    @SuppressWarnings("deprecation")
    private String wifiIdentity(NetworkCapabilities caps) {
        if (!canReadWifi()) return null;
        WifiInfo info = caps.getTransportInfo() instanceof WifiInfo ? (WifiInfo) caps.getTransportInfo() : null;
        if (info == null && Build.VERSION.SDK_INT == 30) {
            WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
            if (wifi != null) info = wifi.getConnectionInfo();
        }
        if (info == null) return null;
        String ssid = info.getSSID();
        if (ssid == null || WifiManager.UNKNOWN_SSID.equals(ssid)) return null;
        return ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")
            ? ssid.substring(1, ssid.length() - 1) : ssid;
    }
    @SuppressWarnings("deprecation")
    private TelephonyManager mobileManager(NetworkCapabilities caps) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null;
        NetworkSpecifier specifier = caps.getNetworkSpecifier();
        int subscription = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if (specifier instanceof TelephonyNetworkSpecifier)
            subscription = ((TelephonyNetworkSpecifier) specifier).getSubscriptionId();
        if (subscription == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            long mobileNetworks = observed.values().stream().filter(value -> value.capabilities != null
                && value.capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && value.capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).count();
            if (mobileNetworks == 1) subscription = SubscriptionManager.getActiveDataSubscriptionId();
        }
        if (subscription == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null;
        TelephonyManager phone = context.getSystemService(TelephonyManager.class);
        if (phone == null || subscriptions == null || subscriptions.getActiveSubscriptionInfo(subscription) == null) return null;
        PhoneWatch watched = phones.get(subscription);
        if (watched != null) return watched.manager;
        TelephonyManager scoped = phone.createForSubscriptionId(subscription);
        PhoneStateListener events = new PhoneStateListener() {
            @Override public void onServiceStateChanged(ServiceState state) { emit(); }
            @Override public void onActiveDataSubscriptionIdChanged(int id) { emit(); }
        };
        phones.put(subscription, new PhoneWatch(scoped, events));
        try { scoped.listen(events, PhoneStateListener.LISTEN_SERVICE_STATE | PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE); }
        catch (RuntimeException e) { phones.remove(subscription); return null; }
        return scoped;
    }
    private void emit() {
        if (closed) return;
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<Network, Observed> item : observed.entrySet()) {
            NetworkCapabilities caps = item.getValue().capabilities;
            LinkProperties links = item.getValue().links;
            if (caps == null || links == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            NetworkSettings.Kind kind;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) kind = NetworkSettings.Kind.WIFI;
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) kind = NetworkSettings.Kind.MOBILE;
            else continue;
            String id = null, label = kind == NetworkSettings.Kind.WIFI ? "Wi-Fi" : "Mobile";
            try {
                if (kind == NetworkSettings.Kind.WIFI) id = wifiIdentity(caps);
                else {
                    TelephonyManager phone = mobileManager(caps);
                    if (phone != null) {
                        String operator = phone.getNetworkOperator();
                        if (operator != null && operator.matches("[0-9]{5,6}")) id = operator;
                        String name = phone.getNetworkOperatorName();
                        if (name != null && !name.isEmpty()) label += " · " + name;
                    }
                }
            } catch (SecurityException ignored) { }
            List<String> addresses = new ArrayList<>();
            for (LinkAddress link : links.getLinkAddresses()) {
                InetAddress address = link.getAddress();
                if (!item.getValue().blocked && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                    && address instanceof Inet4Address && !address.isAnyLocalAddress()
                    && !address.isLoopbackAddress() && !address.isMulticastAddress()) addresses.add(address.getHostAddress());
            }
            Collections.sort(addresses);
            entries.add(new Entry(kind, id, label, links.getInterfaceName(), item.getKey().getNetworkHandle(),
                addresses, NetworkSettings.read(context, kind).decide(id)));
        }
        entries.sort(Comparator.comparingLong(entry -> entry.handle));
        listener.onNetworks(Collections.unmodifiableList(entries));
    }
    @Override public void close() {
        closed = true;
        if (registered) { manager.unregisterNetworkCallback(callback); registered = false; }
        if (receiverRegistered) { context.unregisterReceiver(location); receiverRegistered = false; }
        if (subscriptionsRegistered) { subscriptions.removeOnSubscriptionsChangedListener(subscriptionChanges); subscriptionsRegistered = false; }
        clearPhones();
        observed.clear();
    }
    @SuppressWarnings("deprecation")
    private void clearPhones() {
        for (PhoneWatch phone : phones.values()) {
            try { phone.manager.listen(phone.listener, PhoneStateListener.LISTEN_NONE); }
            catch (RuntimeException ignored) { }
        }
        phones.clear();
    }
}
