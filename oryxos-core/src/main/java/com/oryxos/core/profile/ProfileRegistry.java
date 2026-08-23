package com.oryxos.core.profile;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory index of loaded Profiles, keyed by name. This lesson only has the startup-scan
 * registration path; a runtime {@code register()} method arrives with the lifecycle lesson.
 *
 * @author OryxOS Contributors
 */
public class ProfileRegistry {

  private final Map<String, Profile> profiles = new LinkedHashMap<>();

  /** Creates the registry pre-populated with the given profiles (startup scan result). */
  public ProfileRegistry(Collection<Profile> initialProfiles) {
    if (initialProfiles != null) {
      for (Profile profile : initialProfiles) {
        profiles.put(profile.name(), profile);
      }
    }
  }

  /** Finds a profile by name. */
  public Optional<Profile> find(String name) {
    return Optional.ofNullable(profiles.get(name));
  }
}
