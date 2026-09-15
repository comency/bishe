package cn.edu.lostfound.controller;

import cn.edu.lostfound.service.AiService;
import cn.edu.lostfound.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiValidationTest {
  private AuthService auth;
  private AiService ai;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    auth = mock(AuthService.class);
    ai = mock(AiService.class);
    mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth), new AiController(ai))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  @Test
  void invalidRegistrationReturns400InsteadOf500() throws Exception {
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"ab\",\"password\":\"123456\",\"nickname\":\"同学\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(-1));
    verifyNoInteractions(auth);
  }

  @Test
  void malformedJsonReturns400() throws Exception {
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(-1));
    verifyNoInteractions(auth);
  }

  @Test
  void unsupportedMethodKeeps405() throws Exception {
    mvc.perform(get("/api/auth/login"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value(-1));
  }

  @Test
  void missingAndBlankAiFieldsAreRejectedBeforeCallingProvider() throws Exception {
    mvc.perform(post("/api/ai/polish").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"  \"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(ai);
  }

  @Test
  void validAiRequestPreservesResponseContract() throws Exception {
    when(ai.polish("丢失校园卡")).thenReturn("校园卡遗失，请联系失主。");
    mvc.perform(post("/api/ai/polish").contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"丢失校园卡\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.content").value("校园卡遗失，请联系失主。"));
    verify(ai).polish("丢失校园卡");
  }

  @Test
  void unexpectedErrorsDoNotExposeInternalMessages() throws Exception {
    when(auth.login(any())).thenThrow(new IllegalStateException("internal-database-address"));
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"student\",\"password\":\"123456\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(-1))
        .andExpect(jsonPath("$.message").value("服务器错误，请稍后重试"));
  }
}
