package org.jahia.community.modules;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang.StringUtils;
import org.jahia.bin.filters.AbstractServletFilter;
import org.jahia.services.seo.urlrewrite.ServerNameToSiteMapper;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

@Component(service = AbstractServletFilter.class, scope = ServiceScope.BUNDLE)
public class CrossSitesFilter extends AbstractServletFilter implements Filter {

    private static final String URI_PART_SITES = "/sites/";

    @Activate
    public void activate() {
        setMatchAllUrls(true);
        setOrder(0.5f);
    }

    @Override
    public void init(FilterConfig cfg) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        final HttpServletRequest hsRequest = (HttpServletRequest) request;
        final HttpServletResponse hsResponse = (HttpServletResponse) response;
        final String uri = hsRequest.getRequestURI();
        if (uri.contains(URI_PART_SITES)) {
            final String siteKeyFromPath = StringUtils.substringBetween(uri, URI_PART_SITES, "/");
            final String siteKeyFromServerName = ServerNameToSiteMapper.getSiteKeyByServerName(hsRequest);
            if (siteKeyFromPath != null && !siteKeyFromServerName.isEmpty() && !siteKeyFromPath.equals(siteKeyFromServerName)) {
                hsRequest.setAttribute("prevent_cross_script", "true");
            }
        }
        chain.doFilter(hsRequest, hsResponse);
    }

    @Override
    public void destroy() {
    }
}
