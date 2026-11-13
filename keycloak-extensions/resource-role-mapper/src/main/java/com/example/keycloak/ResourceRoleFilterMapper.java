    apackage com.example.keycloak;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.AccessToken;

/**
 * Narrows the client roles of a token to the ones named by the {@code resource} parameter of the token request.
 *
 * <p>Attach it to a per-DUNS client scope whose scope mappings list ALL roles of that DUNS. The standard "client roles"
 * mapper then puts every role the user holds (within that scope) into {@code resource_access.<client>.roles}; this
 * mapper runs after it and removes every role that was not requested:
 *
 * <pre>
 * client_id=retail-duns-111111111  scope="openid duns-111111111"  resource=RETAILUI   -> roles: [RETAILUI]
 * </pre>
 *
 * It only ever removes. A requested role the user does not hold, or that is outside the scope, is simply absent from the
 * token. No {@code resource} means no roles (deny by default). Several {@code resource} parameters (or a space/comma
 * separated list) request several roles. On refresh-token requests that omit {@code resource}, the value remembered
 * from the original request is used, so a refreshed token keeps the same single role.
 */
public class ResourceRoleFilterMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper {

    public static final String PROVIDER_ID = "resource-role-filter-mapper";
    static final String PARAM = "request.parameter";
    static final String PREFIX = "client.id.prefix";
    private static final String NOTE = "resource-role-filter.resource";

    private static final List<ProviderConfigProperty> CONFIG = new ArrayList<>();
    static {
        CONFIG.add(property(PARAM, "Request parameter", "Name of the token-request parameter that lists the wanted roles.",
                "resource"));
        CONFIG.add(property(PREFIX, "Client id prefix",
                "Only roles of clients whose id starts with this prefix are filtered (the per-DUNS resource clients).",
                "duns-"));
    }

    private static ProviderConfigProperty property(String name, String label, String help, String defaultValue) {
        ProviderConfigProperty p = new ProviderConfigProperty();
        p.setName(name);
        p.setLabel(label);
        p.setHelpText(help);
        p.setType(ProviderConfigProperty.STRING_TYPE);
        p.setDefaultValue(defaultValue);
        return p;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Resource role filter";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Keeps only the client roles named by the 'resource' request parameter; removes all other roles of the DUNS clients.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG;
    }

    /** Must run after the standard role mappers (lower priority runs first). */
    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public AccessToken transformAccessToken(AccessToken token, ProtocolMapperModel mappingModel,
            KeycloakSession session, UserSessionModel userSession, ClientSessionContext clientSessionCtx) {
        String param = config(mappingModel, PARAM, "resource");
        String prefix = config(mappingModel, PREFIX, "duns-");

        Set<String> wanted = requested(session, clientSessionCtx, param);
        Map<String, AccessToken.Access> resourceAccess = token.getResourceAccess();
        if (resourceAccess != null) {
            resourceAccess.entrySet().removeIf(e -> {
                if (!e.getKey().startsWith(prefix)) {
                    return false;
                }
                AccessToken.Access access = e.getValue();
                if (access == null || access.getRoles() == null) {
                    return true;
                }
                access.getRoles().removeIf(role -> !wanted.contains(role));
                return access.getRoles().isEmpty();
            });
            if (resourceAccess.isEmpty()) {
                token.setResourceAccess(null);
            }
        }
        return token;
    }

    private static Set<String> requested(KeycloakSession session, ClientSessionContext ctx, String param) {
        List<String> values = null;
        var request = session.getContext().getHttpRequest();
        if (request != null && request.getDecodedFormParameters() != null) {
            values = request.getDecodedFormParameters().get(param);
        }
        Set<String> roles = split(values);
        if (!roles.isEmpty()) {
            ctx.getClientSession().setNote(NOTE, String.join(" ", roles));
        } else {
            roles = split(List.of(String.valueOf(ctx.getClientSession().getNote(NOTE))));
            roles.remove("null");
        }
        return roles;
    }

    private static Set<String> split(List<String> values) {
        Set<String> roles = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                for (String part : value.split("[\\s,]+")) {
                    if (!part.isBlank()) {
                        roles.add(part);
                    }
                }
            }
        }
        return roles;
    }

    private static String config(ProtocolMapperModel model, String key, String fallback) {
        String v = model.getConfig() == null ? null : model.getConfig().get(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
