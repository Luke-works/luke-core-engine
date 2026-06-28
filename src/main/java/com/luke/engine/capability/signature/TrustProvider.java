package com.luke.engine.capability.signature;

/**
 * SPI for the <b>legally-binding</b> layer (rented, cheap or free): apply a PAdES detached
 * signature + an RFC-3161 trusted timestamp to a prepared PDF, returning the sealed bytes.
 *
 * <p>Two impls behind this seam:
 * <ul>
 *   <li>{@code SelfSealTrustProvider} (V1 default, US ESIGN/UETA): one org document-signing
 *       cert + a free RFC-3161 TSA ≈ $0/signature.</li>
 *   <li>{@code QtspTrustProvider} (EU eIDAS QES/AdES): calls a QTSP per signature — a
 *       config swap, built post-V1.</li>
 * </ul>
 */
public interface TrustProvider {

    byte[] seal(byte[] preparedPdf, SealMeta meta);
}
