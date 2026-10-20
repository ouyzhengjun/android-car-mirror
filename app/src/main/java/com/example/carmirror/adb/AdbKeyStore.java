package com.example.carmirror.adb;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** RSA credentials are stored only inside the app's private files directory. */
public final class AdbKeyStore {
  private final File directory;

  public AdbKeyStore(File filesDirectory) {
    directory = new File(filesDirectory, "adb-identity");
  }

  public synchronized Credentials load() throws Exception {
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建密钥目录");
    File key = new File(directory, "identity.pk8"), cert = new File(directory, "identity.der");
    if (key.exists() && cert.exists())
      return new Credentials(
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(read(key))),
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(read(cert))));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    java.security.KeyPair pair = generator.generateKeyPair();
    long now = System.currentTimeMillis();
    X500Name subject = new X500Name("CN=CarMirror");
    java.security.cert.Certificate certificate =
        new JcaX509CertificateConverter()
            .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
            .getCertificate(
                new JcaX509v3CertificateBuilder(
                        subject,
                        new BigInteger(128, new SecureRandom()),
                        new Date(now - 86400000),
                        new Date(now + 10L * 365 * 86400000),
                        subject,
                        pair.getPublic())
                    .build(
                        new JcaContentSignerBuilder("SHA256withRSA")
                            .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
                            .build(pair.getPrivate())));
    write(key, pair.getPrivate().getEncoded());
    write(cert, certificate.getEncoded());
    return new Credentials(pair.getPrivate(), certificate);
  }

  private static byte[] read(File f) throws IOException {
    try (InputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return out.toByteArray();
    }
  }

  private static void write(File f, byte[] data) throws IOException {
    File temporary = new File(f.getPath() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(temporary)) {
      out.write(data);
      out.getFD().sync();
    }
    if (!temporary.renameTo(f)) throw new IOException("无法保存 ADB 密钥");
  }

  public static final class Credentials {
    public final PrivateKey privateKey;
    public final java.security.cert.Certificate certificate;

    Credentials(PrivateKey key, java.security.cert.Certificate cert) {
      privateKey = key;
      certificate = cert;
    }
  }
}
