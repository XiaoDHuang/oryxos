package com.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

class HttpsFixtureTest {

  @Test
  void trustedClientUsesPrivateCaWhileDefaultTrustRejectsIt() throws Exception {
    Path directory;
    try (HttpsFixture fixture = HttpsFixture.open()) {
      directory = fixture.workDirectory();
      assertThat(directory).isDirectory();
      assertThat(fixture.certificateAuthority().getBasicConstraints()).isNotNegative();
      assertThat(fixture.certificateAuthority().getSubjectX500Principal().getName())
          .contains("OryxOS Test CA");
      assertThat(fixture.certificateAuthority().getKeyUsage()[5]).isTrue();
      assertThat(fixture.serverCertificate().getBasicConstraints()).isNegative();
      assertThat(fixture.serverCertificate().getExtendedKeyUsage()).contains("1.3.6.1.5.5.7.3.1");
      fixture.serverCertificate().verify(fixture.certificateAuthority().getPublicKey());
      assertSubjectAlternativeNames(fixture.serverCertificate().getSubjectAlternativeNames());

      URI endpoint = fixture.uri("/oryx-memory/v1/capabilities");
      assertThat(endpoint.getScheme()).isEqualTo("https");
      assertThat(endpoint.getHost()).isIn("localhost", "127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

      HttpRequest request = HttpRequest.newBuilder(endpoint).GET().build();
      try (HttpClient defaultClient =
          HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
        assertThatThrownBy(
                () -> defaultClient.send(request, HttpResponse.BodyHandlers.discarding()))
            .isInstanceOfAny(SSLHandshakeException.class, IOException.class);
      }

      fixture.server().enqueue(new MockResponse().setResponseCode(200).setBody("受信任响应"));
      try (HttpClient trustedClient =
          HttpClient.newBuilder()
              .sslContext(fixture.clientSslContext())
              .connectTimeout(Duration.ofSeconds(2))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build()) {
        HttpResponse<String> response =
            trustedClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("受信任响应");
      }
      assertThat(fixture.server().takeRequest().getPath())
          .isEqualTo("/oryx-memory/v1/capabilities");
      var previousServer = fixture.server();
      fixture.restartServer();
      assertThat(fixture.server()).isNotSameAs(previousServer);
      assertThat(fixture.uri("/next").getPort()).isNotEqualTo(endpoint.getPort());
      assertThat(fixture.server().getRequestCount()).isZero();
      fixture.server().enqueue(new MockResponse().setBody("新用例"));
      try (HttpClient client =
          HttpClient.newBuilder().sslContext(fixture.clientSslContext()).build()) {
        var next =
            client.send(
                HttpRequest.newBuilder(fixture.uri("/next")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(next.body()).isEqualTo("新用例");
        assertThat(previousServer.getRequestCount()).isEqualTo(1);
        assertThat(fixture.server().getRequestCount()).isEqualTo(1);
      }
    }
    assertThat(directory).doesNotExist();
  }

  @Test
  void onlyRelativeServerPathsAreAcceptedAndCloseIsIdempotent() {
    HttpsFixture fixture = HttpsFixture.open();
    Path directory = fixture.workDirectory();
    assertThat(directory).isDirectory();
    assertThatThrownBy(() -> fixture.uri("https://outside.invalid/value"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture.uri("//outside.invalid/value"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> fixture.uri("/value#fragment"))
        .isInstanceOf(IllegalArgumentException.class);
    fixture.close();
    fixture.close();
    assertThat(directory).doesNotExist();
    assertThatThrownBy(fixture::server).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(fixture::clientSslContext).isInstanceOf(IllegalStateException.class);
  }

  private static void assertSubjectAlternativeNames(Collection<List<?>> names) {
    assertThat(names)
        .anySatisfy(
            name -> {
              assertThat(name.get(0)).isEqualTo(2);
              assertThat(name.get(1)).isEqualTo("localhost");
            })
        .anySatisfy(
            name -> {
              assertThat(name.get(0)).isEqualTo(7);
              assertThat(name.get(1).toString()).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
            });
  }
}
