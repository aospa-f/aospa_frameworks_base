/* SPDX-License-Identifier: Apache-2.0 */
package com.android.server.security.rkp;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;


import org.xmlpull.v1.XmlPullParser;

import android.util.Base64;

/**
 * Minimal reader for the TrickyStore-style AndroidAttestation keybox XML. Only the
 * ECDSA (P-256) keybox that the device RKP flow generates is supported; RSA keyboxes
 * are rejected rather than parsed incorrectly.
 */
final class DuckRkpKeyboxXml {
    private static final byte[] EC_PKCS8_HEADER = {
            0x30, 0x77, 0x02, 0x01, 0x01, 0x04, 0x20
    };
    private static final byte[] EC_PKCS8_TRAILER = {
            (byte) 0xA0, 0x0A, 0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x03,
            0x01, 0x07
    };

    final PrivateKey privateKey;
    final List<X509Certificate> certificates;

    private DuckRkpKeyboxXml(PrivateKey privateKey, List<X509Certificate> certificates) {
        this.privateKey = privateKey;
        this.certificates = certificates;
    }

    boolean isEc() {
        return privateKey != null && privateKey.getAlgorithm().toUpperCase().contains("EC");
    }

    static DuckRkpKeyboxXml parse(String xml) throws Exception {
        if (xml == null || xml.isEmpty()) throw new IllegalArgumentException("Empty keybox");
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) {
            throw new IllegalArgumentException("Keybox contains declarations");
        }
        PrivateKey key = null;
        List<X509Certificate> certificates = new ArrayList<>();
        XmlPullParser parser = android.util.Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        String pem = null;
        boolean inPrivateKey = false;
        boolean inCertificates = false;
        int type = parser.getEventType();
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("PrivateKey".equals(name)) {
                    inPrivateKey = true;
                    pem = null;
                } else if ("CertificateChain".equals(name)) {
                    inCertificates = true;
                } else if ("Certificate".equals(name)) {
                    pem = null;
                } else if ("Key".equals(name)) {
                    String algorithm = parser.getAttributeValue(null, "algorithm");
                    if (algorithm != null && !"ecdsa".equalsIgnoreCase(algorithm)) {
                        throw new IllegalArgumentException("Only ECDSA keyboxes are supported");
                    }
                }
            } else if (type == XmlPullParser.TEXT) {
                String text = parser.getText();
                if (text != null && !text.trim().isEmpty()) {
                    if (inPrivateKey) pem = pem == null ? text.trim() : pem + text.trim();
                    else if (inCertificates) pem = pem == null ? text.trim() : pem + text.trim();
                }
            } else if (type == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if ("PrivateKey".equals(name)) {
                    key = decodePrivateKey(pem);
                    inPrivateKey = false;
                } else if ("Certificate".equals(name)) {
                    certificates.add(decodeCertificate(pem));
                    inCertificates = inCertificates;
                } else if ("CertificateChain".equals(name)) {
                    inCertificates = false;
                }
            }
            type = parser.next();
        }
        if (key == null) throw new IllegalArgumentException("Keybox contains no private key");
        if (certificates.size() < 2) {
            throw new IllegalArgumentException("Keybox contains no certificate chain");
        }
        return new DuckRkpKeyboxXml(key, certificates);
    }

    private static PrivateKey decodePrivateKey(String pem) throws Exception {
        if (pem == null || pem.isEmpty()) throw new IllegalArgumentException("Empty private key");
        byte[] der = Base64.decode(pem, Base64.DEFAULT);
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            // Fall back to a raw SEC1 (BEGIN EC PRIVATE KEY) encoding by wrapping it in PKCS#8.
            if (der.length != 32 + EC_PKCS8_HEADER.length) {
                throw new IllegalArgumentException("Unsupported private key encoding", e);
            }
            byte[] pkcs8 = new byte[EC_PKCS8_HEADER.length + der.length + EC_PKCS8_TRAILER.length];
            System.arraycopy(EC_PKCS8_HEADER, 0, pkcs8, 0, EC_PKCS8_HEADER.length);
            System.arraycopy(der, 0, pkcs8, EC_PKCS8_HEADER.length, der.length);
            System.arraycopy(EC_PKCS8_TRAILER, 0, pkcs8,
                    EC_PKCS8_HEADER.length + der.length, EC_PKCS8_TRAILER.length);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        }
    }

    private static X509Certificate decodeCertificate(String pem) throws Exception {
        if (pem == null || pem.isEmpty()) throw new IllegalArgumentException("Empty certificate");
        byte[] der = Base64.decode(pem, Base64.DEFAULT);
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    /** Throws when the parsed keybox does not describe one consistent EC signing identity. */
    void validateSelfConsistent() throws Exception {
        if (!(privateKey instanceof java.security.interfaces.ECPrivateKey)) {
            throw new IllegalArgumentException("Signing key is not an EC key");
        }
        for (int i = 0; i + 1 < certificates.size(); i++) {
            X509Certificate child = certificates.get(i);
            X509Certificate issuer = certificates.get(i + 1);
            if (!child.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
                throw new IllegalArgumentException("Certificate issuer mismatch");
            }
            child.verify(issuer.getPublicKey());
        }
        java.security.Signature proof = java.security.Signature.getInstance("SHA256withECDSA");
        byte[] challenge = "TrickyStore key match".getBytes(StandardCharsets.UTF_8);
        proof.initSign(privateKey);
        proof.update(challenge);
        byte[] signature = proof.sign();
        proof.initVerify(certificates.get(0).getPublicKey());
        proof.update(challenge);
        if (!proof.verify(signature)) {
            throw new IllegalArgumentException("Private key does not match the leaf certificate");
        }
    }
}
