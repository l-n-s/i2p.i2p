package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.EOFException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.EncType;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.KeyPair;
import net.i2p.crypto.SigType;
import net.i2p.data.DatabaseEntry;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.EncryptedLeaseSet;
import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.Lease2;
import net.i2p.data.LeaseSet;
import net.i2p.data.LeaseSet2;
import net.i2p.data.MetaLease;
import net.i2p.data.MetaLeaseSet;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.RequestLeaseSetMessage;
import net.i2p.util.Log;

/**
 * Handle I2CP RequestLeaseSetMessage from the router by granting all leases,
 * using the specified expiration time for each lease.
 *
 * @author jrandom
 */
class RequestLeaseSetMessageHandler extends HandlerImpl {
    private final Map<Destination, LeaseInfo> _existingLeaseSets;
    protected int _ls2Type = DatabaseEntry.KEY_TYPE_LS2;

    // LS 1
    private static final String PROP_LS_ENCRYPT = "i2cp.encryptLeaseSet";
    private static final String PROP_LS_KEY = "i2cp.leaseSetKey";
    private static final String PROP_LS_PK = "i2cp.leaseSetPrivateKey";
    private static final String PROP_LS_SPK = "i2cp.leaseSetSigningPrivateKey";
    // LS 2
    private static final String PROP_LS_TYPE = "i2cp.leaseSetType";
    private static final String PROP_LS_ENCTYPE = "i2cp.leaseSetEncType";

    public RequestLeaseSetMessageHandler(I2PAppContext context) {
        this(context, RequestLeaseSetMessage.MESSAGE_TYPE);
    }

    /**
     *  For extension
     *  @since 0.9.7
     */
    protected RequestLeaseSetMessageHandler(I2PAppContext context, int messageType) {
        super(context, messageType);
        // not clear why there would ever be more than one
        _existingLeaseSets = new ConcurrentHashMap<Destination, LeaseInfo>(4);
    }
    
    /**
     *  Do we send a LeaseSet or a LeaseSet2?
     *
     *  Side effect: sets _ls2Type
     *
     *  @since 0.9.38
     */
    protected boolean requiresLS2(I2PSessionImpl session) {
        if (!session.supportsLS2())
            return false;
        if (session.isOffline())
            return true;
        String s = session.getOptions().getProperty(PROP_LS_ENCTYPE);
        if (s != null) {
            if (!s.equals("0") && !s.equals("ELGAMAL_2048"))
                return true;
        }
        s = session.getOptions().getProperty(PROP_LS_TYPE);
        if (s != null) {
            try {
                int type = Integer.parseInt(s);
                _ls2Type = type;
                if (type != DatabaseEntry.KEY_TYPE_LEASESET)
                    return true;
            } catch (NumberFormatException nfe) {
              session.propogateError("Bad LS2 type", nfe);
              session.destroySession();
              return true;
            }
        }
        return false;
    }

    public void handleMessage(I2CPMessage message, I2PSessionImpl session) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handle message " + message);
        RequestLeaseSetMessage msg = (RequestLeaseSetMessage) message;
        boolean isLS2 = requiresLS2(session);
        LeaseSet leaseSet;
        if (isLS2) {
            if (_ls2Type == DatabaseEntry.KEY_TYPE_LS2) {
                leaseSet = new LeaseSet2();
            } else if (_ls2Type == DatabaseEntry.KEY_TYPE_ENCRYPTED_LS2) {
                leaseSet = new EncryptedLeaseSet();
            } else if (_ls2Type == DatabaseEntry.KEY_TYPE_META_LS2) {
                leaseSet = new MetaLeaseSet();
            } else {
              session.propogateError("Unsupported LS2 type", new Exception());
              session.destroySession();
              return;
            }
            if (Boolean.parseBoolean(session.getOptions().getProperty("i2cp.dontPublishLeaseSet")))
                ((LeaseSet2)leaseSet).setUnpublished();
        } else {
            leaseSet = new LeaseSet();
        }
        // Full Meta and Encrypted support TODO
        for (int i = 0; i < msg.getEndpoints(); i++) {
            Lease lease;
            if (_ls2Type == DatabaseEntry.KEY_TYPE_META_LS2) {
                lease = new MetaLease();
            } else if (isLS2) {
                lease = new Lease2();
                lease.setTunnelId(msg.getTunnelId(i));
            } else {
                lease = new Lease();
                lease.setTunnelId(msg.getTunnelId(i));
            }
            lease.setGateway(msg.getRouter(i));
            lease.setEndDate(msg.getEndDate());
            //lease.setStartDate(msg.getStartDate());
            leaseSet.addLease(lease);
        }
        signLeaseSet(leaseSet, isLS2, session);
    }

    /**
     *  Finish creating and signing the new LeaseSet
     *  @since 0.9.7
     */
    protected synchronized void signLeaseSet(LeaseSet leaseSet, boolean isLS2, I2PSessionImpl session) {
        Destination dest = session.getMyDestination();
        // also, if this session is connected to multiple routers, include other leases here
        leaseSet.setDestination(dest);

        // reuse the old keys for the client
        LeaseInfo li = _existingLeaseSets.get(dest);
        if (li == null) {
            // [enctype:]b64,... of private keys
            String spk = session.getOptions().getProperty(PROP_LS_PK);
            // [sigtype:]b64 of private key
            String sspk = session.getOptions().getProperty(PROP_LS_SPK);
            List<PrivateKey> privKeys = new ArrayList<PrivateKey>(2);
            SigningPrivateKey signingPrivKey = null;
            if (spk != null && sspk != null) {
                boolean useOldKeys = true;
                int colon = sspk.indexOf(':');
                SigType type = dest.getSigType();
                if (colon > 0) {
                    String stype = sspk.substring(0, colon);
                    SigType t = SigType.parseSigType(stype);
                    if (t == type)
                        sspk = sspk.substring(colon + 1);
                    else
                        useOldKeys = false;
                }
                if (useOldKeys) {
                    try {
                        signingPrivKey = new SigningPrivateKey(type);
                        signingPrivKey.fromBase64(sspk);
                    } catch (DataFormatException dfe) {
                        useOldKeys = false;
                        signingPrivKey = null;
                    }
                }
                if (useOldKeys) {
                    parsePrivateKeys(spk, privKeys);
                }
            }
            if (privKeys.isEmpty() && !_existingLeaseSets.isEmpty()) {
                // look for private keys from another dest using same pubkey
                PublicKey pk = dest.getPublicKey();
                for (Map.Entry<Destination, LeaseInfo> e : _existingLeaseSets.entrySet()) {
                    if (pk.equals(e.getKey().getPublicKey())) {
                        privKeys.addAll(e.getValue().getPrivateKeys());
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Creating new leaseInfo keys for " + dest + " with private key from " + e.getKey());
                        break;
                    }
                }
            }
            if (!privKeys.isEmpty()) {
                if (signingPrivKey != null) {
                    li = new LeaseInfo(privKeys, signingPrivKey);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Creating new leaseInfo keys for " + dest + " WITH configured private keys");
                } else {
                    li = new LeaseInfo(privKeys, dest);
                }
            } else {
                List<EncType> types = new ArrayList<EncType>(2);
                String senc = session.getOptions().getProperty(PROP_LS_ENCTYPE);
                if (senc != null) {
                    String[] senca = DataHelper.split(senc, ",");
                    for (String sencaa : senca) {
                        EncType newtype = EncType.parseEncType(sencaa);
                        if (newtype != null) {
                            if (types.contains(newtype)) {
                                _log.error("Duplicate crypto type: " + newtype);
                                continue;
                            }
                            if (newtype.isAvailable()) {
                                types.add(newtype);
                                if (_log.shouldDebug())
                                    _log.debug("Using crypto type: " + newtype);
                            } else {
                                _log.error("Unsupported crypto type: " + newtype);
                            }
                        } else {
                            _log.error("Unsupported crypto type: " + sencaa);
                        }
                    }
                }
                if (types.isEmpty()) {
                    if (_log.shouldDebug())
                        _log.debug("Using default crypto type");
                    types.add(EncType.ELGAMAL_2048);
                }
                li = new LeaseInfo(dest, types);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Creating new leaseInfo keys for " + dest + " without configured private keys");
            }
            _existingLeaseSets.put(dest, li);
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Caching the old leaseInfo keys for " 
                           + dest);
        }

        if (isLS2) {
            LeaseSet2 ls2 = (LeaseSet2) leaseSet;
            if (_ls2Type != DatabaseEntry.KEY_TYPE_META_LS2) {
                for (PublicKey key : li.getPublicKeys()) {
                    ls2.addEncryptionKey(key);
                }
            }
        } else {
            leaseSet.setEncryptionKey(li.getPublicKey());
        }
        leaseSet.setSigningKey(li.getSigningPublicKey());
        // SubSession options aren't updated via the gui, so use the primary options
        Properties opts;
        if (session instanceof SubSession)
            opts = ((SubSession) session).getPrimaryOptions();
        else
            opts = session.getOptions();
        boolean encrypt = Boolean.parseBoolean(opts.getProperty(PROP_LS_ENCRYPT));
        String sk = opts.getProperty(PROP_LS_KEY);
        Hash h = dest.calculateHash();
        if (encrypt && sk != null) {
            SessionKey key = new SessionKey();
            try {
                key.fromBase64(sk);
                leaseSet.encrypt(key);
                _context.keyRing().put(h, key);
            } catch (DataFormatException dfe) {
                _log.error("Bad leaseset key: " + sk);
                _context.keyRing().remove(h);
            }
        } else {
            _context.keyRing().remove(h);
        }
        // offline keys
        if (session.isOffline()) {
            LeaseSet2 ls2 = (LeaseSet2) leaseSet;
            boolean ok = ls2.setOfflineSignature(session.getOfflineExpiration(), session.getTransientSigningPublicKey(),
                                                 session.getOfflineSignature());
            if (!ok) {
                session.propogateError("Bad offline signature", new Exception());
                session.destroySession();
            }
        }
        try {
            leaseSet.sign(session.getPrivateKey());
            // Workaround for unparsable serialized signing private key for revocation
            // Send him a dummy DSA_SHA1 private key since it's unused anyway
            // See CreateLeaseSetMessage.doReadMessage()
            // For LS1 only
            SigningPrivateKey spk = li.getSigningPrivateKey();
            if (!_context.isRouterContext() && spk.getType() != SigType.DSA_SHA1 &&
                !(leaseSet instanceof LeaseSet2)) {
                byte[] dummy = new byte[SigningPrivateKey.KEYSIZE_BYTES];
                _context.random().nextBytes(dummy);
                spk = new SigningPrivateKey(dummy);
            }
            session.getProducer().createLeaseSet(session, leaseSet, spk, li.getPrivateKeys());
            session.setLeaseSet(leaseSet);
            if (_log.shouldDebug())
                _log.debug("Created and signed LeaseSet: " + leaseSet);
        } catch (DataFormatException dfe) {
            session.propogateError("Error signing the leaseSet", dfe);
            session.destroySession();
        } catch (I2PSessionException ise) {
            if (session.isClosed()) {
                // race, closed while signing leaseset
                // EOFExceptions are logged at WARN level (see I2PSessionImpl.propogateError())
                // so the user won't see this
                EOFException eof = new EOFException("Session closed while signing leaseset");
                eof.initCause(ise);
                session.propogateError("Session closed while signing leaseset", eof);
            } else {
                session.propogateError("Error sending the signed leaseSet", ise);
            }
        }
    }

    /**
     *  @param spk non-null [type:]b64[,[type:]b64]...
     *  @param privKeys out parameter
     *  @since 0.9.39
     */
    private void parsePrivateKeys(String spkl, List<PrivateKey> privKeys) {
        String[] spks = DataHelper.split(spkl, ",");
        for (String spk : spks) {
            int colon = spk.indexOf(':');
            if (colon > 0) {
                EncType type = EncType.parseEncType(spk.substring(0, colon));
                if (type != null) {
                    if (type.isAvailable()) {
                        try {
                            PrivateKey privKey = new PrivateKey(type);
                            privKey.fromBase64(spk.substring(colon + 1));
                            privKeys.add(privKey);
                            if (_log.shouldDebug())
                                _log.debug("Using crypto type: " + type);
                        } catch (DataFormatException dfe) {
                            _log.error("Bad private key: " + spk, dfe);
                        }
                    } else {
                        _log.error("Unsupported crypto type: " + type);
                    }
                } else {
                    _log.error("Unsupported crypto type: " + spk);
                }
            } else if (colon < 0) {
                try {
                    PrivateKey privKey = new PrivateKey();
                    privKey.fromBase64(spk);
                    privKeys.add(privKey);
                } catch (DataFormatException dfe) {
                    _log.error("Bad private key: " + spk, dfe);
                }
            } else {
                _log.error("Empty crypto type");
            }
        }
    }

    /**
     *  Multiple encryption keys supported, as of 0.9.39, for LS2
     */
    private static class LeaseInfo {
        private final List<PublicKey> _pubKeys;
        private final List<PrivateKey> _privKeys;
        private final SigningPublicKey _signingPubKey;
        private final SigningPrivateKey _signingPrivKey;

        /**
         *  New keys
         *  @param types must be available
         */
        public LeaseInfo(Destination dest, List<EncType> types) {
            if (types.size() > 1) {
                Collections.sort(types, Collections.reverseOrder());
            }
            _privKeys = new ArrayList<PrivateKey>(types.size());
            _pubKeys = new ArrayList<PublicKey>(types.size());
            for (EncType type : types) {
                KeyPair encKeys = KeyGenerator.getInstance().generatePKIKeys(type);
                _pubKeys.add(encKeys.getPublic());
                _privKeys.add(encKeys.getPrivate());
            }
            // must be same type as the Destination's signing key
            SimpleDataStructure signKeys[];
            try {
                signKeys = KeyGenerator.getInstance().generateSigningKeys(dest.getSigningPublicKey().getType());
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException(gse);
            }
            _signingPubKey = (SigningPublicKey) signKeys[0];
            _signingPrivKey = (SigningPrivateKey) signKeys[1];
        }

        /**
         *  Existing keys
         *  @param privKeys all EncTypes must be available
         *  @since 0.9.18
         */
        public LeaseInfo(List<PrivateKey> privKeys, SigningPrivateKey signingPrivKey) {
            if (privKeys.size() > 1) {
                Collections.sort(privKeys, new PrivKeyComparator());
            }
            _privKeys = privKeys;
            _pubKeys = new ArrayList<PublicKey>(privKeys.size());
            for (PrivateKey privKey : privKeys) {
                _pubKeys.add(KeyGenerator.getPublicKey(privKey));
            }
            _signingPubKey = KeyGenerator.getSigningPublicKey(signingPrivKey);
            _signingPrivKey = signingPrivKey;
        }

        /**
         *  Existing crypto keys, new signing key
         *  @param privKeys all EncTypes must be available
         *  @since 0.9.21
         */
        public LeaseInfo(List<PrivateKey> privKeys, Destination dest) {
            SimpleDataStructure signKeys[];
            try {
                signKeys = KeyGenerator.getInstance().generateSigningKeys(dest.getSigningPublicKey().getType());
            } catch (GeneralSecurityException gse) {
                throw new IllegalStateException(gse);
            }
            _privKeys = privKeys;
            _pubKeys = new ArrayList<PublicKey>(privKeys.size());
            for (PrivateKey privKey : privKeys) {
                _pubKeys.add(KeyGenerator.getPublicKey(privKey));
            }
            _signingPubKey = (SigningPublicKey) signKeys[0];
            _signingPrivKey = (SigningPrivateKey) signKeys[1];
        }

        /** @return the first one if more than one */
        public PublicKey getPublicKey() {
            return _pubKeys.get(0);
        }

        /** @return the first one if more than one */
        public PrivateKey getPrivateKey() {
            return _privKeys.get(0);
        }

        /** @since 0.9.39 */
        public List<PublicKey> getPublicKeys() {
            return _pubKeys;
        }

        /** @since 0.9.39 */
        public List<PrivateKey> getPrivateKeys() {
            return _privKeys;
        }

        public SigningPublicKey getSigningPublicKey() {
            return _signingPubKey;
        }

        public SigningPrivateKey getSigningPrivateKey() {
            return _signingPrivKey;
        }

        /**
         *  Reverse order by enc type
         *  @since 0.9.39
         */
        private static class PrivKeyComparator implements Comparator<PrivateKey> {
            public int compare(PrivateKey l, PrivateKey r) {
                return r.getType().compareTo(l.getType());
            }
        }
    }
}
