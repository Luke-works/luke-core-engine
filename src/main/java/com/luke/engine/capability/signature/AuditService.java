package com.luke.engine.capability.signature;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

/**
 * Writes the IP-stamped audit trail. Every lifecycle event is recorded here so each row gets
 * the real client IP (via {@link ClientIp}), user-agent, the {@link IpRisk} classification,
 * and optional geo — uniformly across the authed and public controllers (SIG-3).
 */
@Service
public class AuditService {

    private final SignatureAuditEventRepository auditRepo;
    private final IpReputationProvider ipReputation;
    private final IpGeoProvider ipGeo;

    public AuditService(SignatureAuditEventRepository auditRepo,
                        IpReputationProvider ipReputation,
                        IpGeoProvider ipGeo) {
        this.auditRepo = auditRepo;
        this.ipReputation = ipReputation;
        this.ipGeo = ipGeo;
    }

    /** Record an event stamped with the request's IP / user-agent / risk / geo. */
    public SignatureAuditEvent record(SignatureRequest request, String action, String actor,
                                      HttpServletRequest http) {
        return record(request, action, actor, http, null);
    }

    /** As above, with free-form {@code detail} (e.g. "consent=true", "blocked: TOR"). */
    public SignatureAuditEvent record(SignatureRequest request, String action, String actor,
                                      HttpServletRequest http, String detail) {
        SignatureAuditEvent event = new SignatureAuditEvent(
                request.getId(), request.getTenantId(), action, actor);
        event.setDetail(detail);

        String ip = ClientIp.resolve(http);
        if (ip != null) {
            event.setIpAddress(ip);
            event.setIpRisk(ipReputation.classify(ip));
            IpGeoProvider.Geo geo = ipGeo.lookup(ip);
            if (geo != null) {
                event.setGeoCountry(geo.country());
                event.setGeoCity(geo.city());
            }
        }
        if (http != null) {
            event.setUserAgent(http.getHeader("User-Agent"));
        }
        return auditRepo.save(event);
    }
}
