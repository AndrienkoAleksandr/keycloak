package org.keycloak.federation.ldap.mappers;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.keycloak.federation.ldap.LDAPFederationProvider;
import org.keycloak.federation.ldap.idm.model.LDAPObject;
import org.keycloak.federation.ldap.idm.query.Condition;
import org.keycloak.federation.ldap.idm.query.QueryParameter;
import org.keycloak.federation.ldap.idm.query.internal.LDAPIdentityQuery;
import org.keycloak.models.UserFederationMapperModel;
import org.keycloak.models.UserFederationProvider;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.reflection.Property;
import org.keycloak.models.utils.reflection.PropertyCriteria;
import org.keycloak.models.utils.reflection.PropertyQueries;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class UserAttributeLDAPFederationMapper extends AbstractLDAPFederationMapper {

    private static final Map<String, Property<Object>> userModelProperties;

    static {
        userModelProperties = PropertyQueries.createQuery(UserModel.class).addCriteria(new PropertyCriteria() {

            @Override
            public boolean methodMatches(Method m) {
                if ((m.getName().startsWith("get") || m.getName().startsWith("is")) && m.getParameterTypes().length > 0) {
                    return false;
                }

                return true;
            }

        }).getResultList();
    }

    public static final String USER_MODEL_ATTRIBUTE = "user.model.attribute";
    public static final String LDAP_ATTRIBUTE = "ldap.attribute";
    public static final String READ_ONLY = "read.only";

    @Override
    public String getHelpText() {
        return "Some help text TODO";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return null;
    }

    @Override
    public String getId() {
        return "ldap-user-attribute-mapper";
    }

    @Override
    public void importUserFromLDAP(UserFederationMapperModel mapperModel, LDAPFederationProvider ldapProvider, LDAPObject ldapObject, UserModel user, boolean isCreate) {
        String userModelAttrName = mapperModel.getConfig().get(USER_MODEL_ATTRIBUTE);
        String ldapAttrName = mapperModel.getConfig().get(LDAP_ATTRIBUTE);

        Serializable ldapAttrValue = ldapObject.getAttribute(ldapAttrName);
        if (ldapAttrValue != null) {
            Property<Object> userModelProperty = userModelProperties.get(userModelAttrName);

            if (userModelProperty != null) {
                // we have java property on UserModel
                userModelProperty.setValue(user, ldapAttrValue);
            } else {
                // we don't have java property. Let's just setAttribute
                user.setAttribute(userModelAttrName, (String) ldapAttrValue);
            }
        }
    }

    @Override
    public void registerUserToLDAP(UserFederationMapperModel mapperModel, LDAPFederationProvider ldapProvider, LDAPObject ldapObject, UserModel localUser) {
        String userModelAttrName = mapperModel.getConfig().get(USER_MODEL_ATTRIBUTE);
        String ldapAttrName = mapperModel.getConfig().get(LDAP_ATTRIBUTE);

        Property<Object> userModelProperty = userModelProperties.get(userModelAttrName);

        Object attrValue;
        if (userModelProperty != null) {
            // we have java property on UserModel
            attrValue = userModelProperty.getValue(localUser);
        } else {
            // we don't have java property. Let's just setAttribute
            attrValue = localUser.getAttribute(userModelAttrName);
        }

        ldapObject.setAttribute(ldapAttrName, (Serializable) attrValue);
        if (isReadOnly(mapperModel)) {
            ldapObject.addReadOnlyAttributeName(ldapAttrName);
        }
    }

    @Override
    public UserModel proxy(UserFederationMapperModel mapperModel, LDAPFederationProvider ldapProvider, LDAPObject ldapObject, UserModel delegate) {
        if (ldapProvider.getEditMode() == UserFederationProvider.EditMode.WRITABLE && !isReadOnly(mapperModel)) {

            // This assumes that mappers are sorted by type! Maybe improve...
            TxAwareLDAPUserModelDelegate txDelegate;
            if (delegate instanceof TxAwareLDAPUserModelDelegate) {
                // We will reuse already existing delegate and just register our mapped attribute in existing transaction.
                txDelegate = (TxAwareLDAPUserModelDelegate) delegate;
            } else {
                txDelegate = new TxAwareLDAPUserModelDelegate(delegate, ldapProvider, ldapObject);
            }

            String userModelAttrName = mapperModel.getConfig().get(USER_MODEL_ATTRIBUTE);
            String ldapAttrName = mapperModel.getConfig().get(LDAP_ATTRIBUTE);
            txDelegate.addMappedAttribute(userModelAttrName, ldapAttrName);

            return txDelegate;
        } else {
            return delegate;
        }
    }

    @Override
    public void beforeLDAPQuery(UserFederationMapperModel mapperModel, LDAPIdentityQuery query) {
        String userModelAttrName = mapperModel.getConfig().get(USER_MODEL_ATTRIBUTE);
        String ldapAttrName = mapperModel.getConfig().get(LDAP_ATTRIBUTE);

        // Add mapped attribute to returning ldap attributes
        query.addReturningLdapAttribute(ldapAttrName);
        if (isReadOnly(mapperModel)) {
            query.addReturningReadOnlyLdapAttribute(ldapAttrName);
        }

        // Change conditions and use ldapAttribute instead of userModel
        for (Condition condition : query.getConditions()) {
            QueryParameter param = condition.getParameter();
            if (param != null && param.getName().equals(userModelAttrName)) {
                param.setName(ldapAttrName);
            }
        }
    }

    private boolean isReadOnly(UserFederationMapperModel mapperModel) {
        String readOnly = mapperModel.getConfig().get(READ_ONLY);
        return Boolean.parseBoolean(readOnly);
    }
}
