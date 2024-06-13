package org.jahia.community.modules;

import org.apache.commons.lang.StringUtils;
import org.jahia.bin.filters.AbstractServletFilter;
import org.jahia.bin.filters.ServletFilter;
import org.jahia.services.seo.urlrewrite.ServerNameToSiteMapper;
import org.osgi.service.component.annotations.Component;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component(service = AbstractServletFilter.class)
public class CrossSitesFilter extends ServletFilter {
    private static final String URI_PART_SITES = "/sites/";

    public CrossSitesFilter() {
        setUrlPatterns(new String[]{".*" + URI_PART_SITES + ".*"});
        setOrder(0.5f);
    }

    @Override
    public void init(FilterConfig cfg) {
        // Do nothing
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest hsRequest = (HttpServletRequest) request;
        final HttpServletResponse hsResponse = (HttpServletResponse) response;
        final String siteKeyFromPath = StringUtils.substringBetween(hsRequest.getRequestURI(), URI_PART_SITES, "/");
        final String siteKeyFromServerName = ServerNameToSiteMapper.getSiteKeyByServerName(hsRequest);
        if (siteKeyFromPath != null && !siteKeyFromServerName.isEmpty() && !siteKeyFromPath.equals(siteKeyFromServerName)) {
            hsRequest.setAttribute("prevent_cross_script", "true");
        }
        chain.doFilter(hsRequest, hsResponse);
    }

    @Override
    public void destroy() {
        // Do nothing
    }
}
