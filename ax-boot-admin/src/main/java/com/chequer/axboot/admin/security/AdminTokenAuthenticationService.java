package com.chequer.axboot.admin.security;

import com.chequer.axboot.admin.code.GlobalConstants;
import com.chequer.axboot.admin.domain.program.Program;
import com.chequer.axboot.admin.domain.program.ProgramService;
import com.chequer.axboot.admin.domain.program.menu.Menu;
import com.chequer.axboot.admin.domain.program.menu.MenuService;
import com.chequer.axboot.admin.domain.user.auth.menu.AuthGroupMenu;
import com.chequer.axboot.admin.domain.user.auth.menu.AuthGroupMenuService;
import com.chequer.axboot.core.code.AXBootTypes;
import com.chequer.axboot.core.domain.user.SessionUser;
import com.chequer.axboot.core.session.JWTSessionHandler;
import com.chequer.axboot.core.utils.*;
import com.chequer.axboot.core.vo.ScriptSessionVO;
import org.apache.commons.io.FilenameUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.util.List;

@Service
public class AdminTokenAuthenticationService {

    private final JWTSessionHandler jwtSessionHandler;

    @Inject
    private ProgramService programService;

    @Inject
    private AuthGroupMenuService authGroupMenuService;

    @Inject
    private MenuService menuService;

    public AdminTokenAuthenticationService() {
        jwtSessionHandler = new JWTSessionHandler(DatatypeConverter.parseBase64Binary("YXhib290"));
    }

    public int tokenExpiry() {
        if (PhaseUtils.isAlpha()) {
            return 60 * 3;
        } else if (PhaseUtils.isProduction()) {
            return 60 * 50;
        } else {
            return 60 * 10 * 10 * 10 * 10;
        }
    }

    public void addAuthentication(HttpServletResponse response, AdminUserAuthentication authentication) throws IOException {
        final SessionUser user = authentication.getDetails();
        setUserEnvironments(user, response);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    public void setUserEnvironments(SessionUser user, HttpServletResponse response) throws IOException {
        String token = jwtSessionHandler.createTokenForUser(user);
        CookieUtils.addCookie(response, GlobalConstants.ADMIN_AUTH_TOKEN_KEY, token, tokenExpiry());
    }

    public Authentication getAuthentication(HttpServletRequest request, HttpServletResponse response) throws IOException {
        RequestUtils requestUtils = RequestUtils.of(request);
        final String token = CookieUtils.getCookieValue(request, GlobalConstants.ADMIN_AUTH_TOKEN_KEY);
        final String progCd = FilenameUtils.getBaseName(request.getServletPath());
        final Long menuId = requestUtils.getLong("menuId");
        final String requestUri = request.getRequestURI();

        if (token == null) {
            return deleteCookieAndReturnNullAuthentication(request, response);
        }

        SessionUser user = jwtSessionHandler.parseUserFromToken(token);

        if (user == null) {
            return deleteCookieAndReturnNullAuthentication(request, response);
        }

        if (!requestUri.startsWith(ContextUtil.getBaseApiPath())) {
            Menu menu = menuService.findOne(menuId);

            if (menu != null) {
                Program program = menu.getProgram();

                if (program != null) {
                    requestUtils.setAttribute("program", program);
                    requestUtils.setAttribute("pageName", menu.getMenuNm());
                    requestUtils.setAttribute("pageRemark", program.getRemark());

                    if (program.getAuthCheck().equals(AXBootTypes.Used.YES.getLabel())) {
                        AuthGroupMenu authGroupMenu = authGroupMenuService.getCurrentAuthGroupMenu(menuId, user);
                        if (authGroupMenu == null) {
                            throw new AccessDeniedException("Access is denied");
                        }
                        requestUtils.setAttribute("authGroupMenu", authGroupMenu);
                    }
                }
            }

            ScriptSessionVO scriptSessionVO = ModelMapperUtils.map(user, ScriptSessionVO.class);
            scriptSessionVO.setDateFormat(scriptSessionVO.getDateFormat().toUpperCase());
            requestUtils.setAttribute("loginUser", user);
            requestUtils.setAttribute("scriptSession", JsonUtils.toJson(scriptSessionVO));

            if (progCd.equals("main")) {
                List<Menu> menuList = menuService.getAuthorizedMenuList(user.getMenuGrpCd(), user.getAuthGroupList());
                requestUtils.setAttribute("menuJson", JsonUtils.toJson(menuList));
            }

            setUserEnvironments(user, response);
        }

        return new AdminUserAuthentication(user);
    }

    private Authentication deleteCookieAndReturnNullAuthentication(HttpServletRequest request, HttpServletResponse response) {
        CookieUtils.deleteCookie(request, response, GlobalConstants.ADMIN_AUTH_TOKEN_KEY);
        ScriptSessionVO scriptSessionVO = ScriptSessionVO.noLoginSession();
        request.setAttribute("scriptSession", JsonUtils.toJson(scriptSessionVO));
        return null;
    }
}
