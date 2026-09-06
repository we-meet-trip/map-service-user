package map.service.user.recommend;

public final class TestJobOwners {
    private TestJobOwners() {}
    public static map.service.user.domain.user.repository.UserRepository active() {
        var users = org.mockito.Mockito.mock(map.service.user.domain.user.repository.UserRepository.class);
        org.mockito.Mockito.when(users.findByIdForUpdate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(org.mockito.Mockito.mock(map.service.user.domain.user.entity.User.class)));
        return users;
    }
}
