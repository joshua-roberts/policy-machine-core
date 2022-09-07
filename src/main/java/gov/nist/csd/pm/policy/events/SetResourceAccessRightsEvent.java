package gov.nist.csd.pm.policy.events;

import gov.nist.csd.pm.policy.model.access.AccessRightSet;

public class SetResourceAccessRightsEvent extends PolicyEvent {

    private AccessRightSet accessRightSet;

    public SetResourceAccessRightsEvent(AccessRightSet accessRightSet) {
        this.accessRightSet = accessRightSet;
    }

    public AccessRightSet getAccessRightSet() {
        return accessRightSet;
    }
}
