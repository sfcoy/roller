/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.weblogger.util.cache;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.WeblogBookmark;
import org.apache.roller.weblogger.pojos.WeblogEntryComment;
import org.apache.roller.weblogger.pojos.WeblogBookmarkFolder;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.pojos.Weblog;


/**
 * A governing class for Roller cache objects.
 *
 * The purpose of the CacheManager is to provide a level of abstraction between
 * classes that use a cache and the implementations of a cache.  This allows
 * us to create easily pluggable cache implementations.
 * 
 * The other purpose is to provide a single interface for interacting with all
 * Roller caches at the same time.  This is beneficial because as data
 * changes in the system we often need to notify all caches that some part of
 * their cached data needs to be invalidated, and the CacheManager makes that
 * process easier.
 */
public final class CacheManager {
    
    private static Log log = LogFactory.getLog(CacheManager.class);
    
    private static final String DEFAULT_FACTORY = 
            "org.apache.roller.weblogger.util.cache.ExpiringLRUCacheFactoryImpl";
    
    // a reference to the cache factory in use
    private static CacheFactory cacheFactory = null;
    
    // a set of all registered cache handlers
    private static Set<CacheHandler> cacheHandlers = new HashSet<CacheHandler>();
    
    // a map of all registered caches
    private static Map<String, Cache> caches = new HashMap<String, Cache>();
    
    
    static {
        // lookup what cache factory we want to use
        String classname = WebloggerConfig.getProperty("cache.defaultFactory");
        
        // use reflection to instantiate our factory class
        try {
            Class factoryClass = Class.forName(classname);
            cacheFactory = (CacheFactory) factoryClass.newInstance();
        } catch(ClassCastException cce) {
            log.error("It appears that your factory does not implement "+
                    "the CacheFactory interface",cce);
        } catch(Exception e) {
            log.error("Unable to instantiate cache factory ["+classname+"]"+
                    " falling back on default", e);
        }
        
        if(cacheFactory == null) {
            try {
                // hmm ... failed to load the specified cache factory
                // lets try our default
                Class factoryClass = Class.forName(DEFAULT_FACTORY);
                cacheFactory = (CacheFactory) factoryClass.newInstance();
            } catch(Exception e) {
                log.fatal("Failed to instantiate a cache factory", e);
                throw new RuntimeException(e);
            }
        }
        
        log.info("Cache Manager Initialized.");
        log.info("Cache Factory = "+cacheFactory.getClass().getName());
        
        
        // add custom handlers
        String customHandlers = WebloggerConfig.getProperty("cache.customHandlers");
        if(customHandlers != null && customHandlers.trim().length() > 0) {
            
            String[] cHandlers = customHandlers.split(",");
            for (String cHandler : cHandlers) {
                // use reflection to instantiate the handler class
                try {
                    Class handlerClass = Class.forName(cHandler);
                    CacheHandler customHandler = 
                            (CacheHandler) handlerClass.newInstance();
                    
                    cacheHandlers.add(customHandler);
                } catch(ClassCastException cce) {
                    log.error("It appears that your handler does not implement "+
                            "the CacheHandler interface",cce);
                } catch(Exception e) {
                    log.error("Unable to instantiate cache handler ["+cHandler+"]", e);
                }
            }
        }
    }
    
    
    // a non-instantiable class
    private CacheManager() {}
    
    
    /**
     * Ask the CacheManager to construct a cache.
     *
     * Normally the CacheManager will use whatever CacheFactory has been
     * chosen for the system via the cache.defaultFactory property.
     * However, it is possible to override the use of the default factory by
     * supplying a "factory" property to this method.  The value should
     * be the full classname for the factory you want to use for constructing
     * the cache.
     *
     * example:
     *   factory -> org.apache.roller.weblogger.util.cache.LRUCacheFactoryImpl
     *
     * This allows Roller admins the ability to choose a caching strategy to
     * use for the whole system, but override it in certain places where they
     * see fit.  It also allows users to write their own caching modifications
     * and have them used only by specific caches.
     */
    public static Cache constructCache(CacheHandler handler, Map properties) {
        
        log.debug("Constructing new cache with props "+properties);
        
        Cache cache = null;
        
        if(properties != null && properties.containsKey("factory")) {
            // someone wants a custom cache instance
            String classname = (String) properties.get("factory");
            
            try {
                // use reflection to instantiate the factory class
                Class factoryClass = Class.forName(classname);
                CacheFactory factory = (CacheFactory) factoryClass.newInstance();
                
                // now ask for a new cache
                cache = factory.constructCache(properties);
            } catch(ClassCastException cce) {
                log.error("It appears that your factory ["+classname+
                        "] does not implement the CacheFactory interface",cce);
            } catch(Exception e) {
                log.error("Unable to instantiate cache factory ["+classname+
                        "] falling back on default", e);
            }
        }
        
        if(cache == null) {
            // ask our default cache factory for a new cache instance
            cache = cacheFactory.constructCache(properties);
        }
        
        if(cache != null) {
            caches.put(cache.getId(), cache);
            
            // register the handler for this new cache
            if(handler != null) {
                cacheHandlers.add(handler);
            }
        }

        return cache;
    }
    
    
    /**
     * Register a CacheHandler to listen for object invalidations.
     *
     * This is here so that it's possible to to add classes which would respond
     * to object invalidations without necessarily having to create a cache.
     *
     * An example would be a handler designed to notify other machines in a 
     * cluster when an object has been invalidated, or possibly the search
     * index management classes are interested in knowing when objects are
     * invalidated.
     */
    public static void registerHandler(CacheHandler handler) {

        log.debug("Registering handler "+handler);

        if(handler != null) {
            cacheHandlers.add(handler);
        }
    }
    
    
    public static void invalidate(WeblogEntry entry) {
        
        log.debug("invalidating entry = "+entry.getAnchor());
        for (CacheHandler handler : cacheHandlers) {
            handler.invalidate(entry);
        }
    }
    
    
    public static void invalidate(Weblog website) {
        
        log.debug("invalidating website = "+website.getHandle());
        for (CacheHandler handler : cacheHandlers) {
            handler.invalidate(website);
        }
    }
    
    
    public static void invalidate(WeblogBookmark bookmark) {
        
        log.debug("invalidating bookmark = "+bookmark.getId());
        for (CacheHandler handler : cacheHandlers) {
            handler.invalidate(bookmark);
        }
    }
    
    
    public static void invalidate(WeblogBookmarkFolder folder) {
        
        log.debug("invalidating folder = "+folder.getId());
        for (CacheHandler handler : cacheHandlers) {
            handler.invalidate(folder);
        }
    }
    
    
    public static void invalidate(WeblogEntryComment comment) {
        
        log.debug("invalidating comment = "+comment.getId());
        for (CacheHandler handler : cacheHandlers) {
            handler.invalidate(comment);
        }
    }
    
    
    public static void invalidate(User user) {
        
        log.debug("invalidating user = "+user.getUserName());
        for (CacheHandler handler : cacheHandlers) {
            handler.invalidate(user);
        }
    }
    
    
    public static void invalidate(WeblogCategory category) {
        
        log.debug("invalidating category = " + category.getId());
        for (CacheHandler handler : cacheHandlers) {
            handler.invalidate(category);
        }
    }
    
    
    public static void invalidate(WeblogTemplate template) {
        log.debug("invalidating template = " + template.getId());
        for (CacheHandler handler : cacheHandlers) {
            handler.invalidate(template);
        }
    }

    
    /**
     * Flush the entire cache system.
     */
    public static void clear() {
        for (Cache cache : caches.values()) {
            cache.clear();
        }
    }
    
    
    /**
     * Flush a single cache.
     */
    public static void clear(String cacheId) {
        Cache cache = caches.get(cacheId);
        if(cache != null) {
            cache.clear();
        }
    }
    
    
    /**
     * Compile stats from all registered caches.
     *
     * This is basically a hacky version of instrumentation which is being
     * thrown in because we don't have a full instrumentation strategy yet.
     * This is here with the full expectation that it will be replaced by
     * something a bit more elaborate, like JMX.
     */
    public static Map<String, Map<String, Object>> getStats() {
        Map<String, Map<String, Object>> allStats = new HashMap<String, Map<String, Object>>();
        for (Cache cache : caches.values()) {
            allStats.put(cache.getId(), cache.getStats());
        }
        return allStats;
    }
    
    
    /**
     * Place to do any cleanup tasks for cache system.
     */
    public static void shutdown() {
        // no-op
    }
    
}
