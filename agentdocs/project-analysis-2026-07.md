<!DOCTYPE html><html lang="en-US" class="" data-primer data-cdn="https://a.slack-edge.com/"><head><script>

(function () {
	
	var data;

	const MAX_CDN_FALLBACK = 2;
	let isReloading = false;

	function scanPageAssets() {
		var css = document.getElementsByTagName('link');
		var script = document.getElementsByTagName('script');
		var i;
		var j;

		
		var cdn_domain = 'slack-edge.com';

		
		var domains = location.hostname && location.hostname.split('.');

		const isDev =
			(domains && domains.length && domains[0] && domains[0].match(/dev[0-9]+/i)) ||
			(domains[1] && domains[1].match(/dev[0-9]+/i));

		
		if (isDev) {
			cdn_domain = location.hostname;
		}

		for (i = 0, j = css.length; i < j; i += 1) {
			if (
				css[i].rel === 'stylesheet' &&
				css[i].href &&
				(!cdn_domain || css[i].href.includes(cdn_domain))
			)
				count(css[i]);
		}

		for (i = 0, j = script.length; i < j; i += 1) {
			if (script[i].src && (!cdn_domain || script[i].src.includes(cdn_domain)))
				count(script[i]);
		}

		
		check();
	}

	function reset() {
		data = {
			node_count: 0,
			known_nodes: [],
			processed_nodes: [],
			processed_count: 0,
			loaded: [],
			failed: [],
		};
	}

	function isRelevantNode(node) {
		if (!node) return;
		
		if (!node.onload || !node.onerror) return false;
		
		if (node.href && node.rel && node.rel === 'stylesheet') return true;
		
		if (node.src && node.nodeName.toLowerCase() === 'script') return true;
		
		return false;
	}

	function getURL(node) {
		if (!node) return;
		
		return node.href || node.src;
	}

	function process(node) {
		var src = getURL(node);
		if (!src) return;
		
		if (data.processed_nodes[src]) return;
		data.processed_nodes[src] = true;
		return true;
	}

	function ok(node, args) {
		if (!process(node)) return;
		data.processed_count += 1;
		data.loaded.push({
			node: node,
			args: args,
		});
		check();
	}

	function failed(node, args) {
		if (!process(node)) return;
		data.processed_count += 1;
		data.failed.push({
			node: node,
			args: args,
		});
		
		(
			(window.TS && TS.console && TS.console.error && TS.console.error) ||
			window.console.error ||
			function () {}
		)('CDN asset failed to load: ' + getURL(node));
		check();
	}

	function count(node) {
		if (!node) return;
		if (!isRelevantNode(node)) return;
		var url = getURL(node);
		if (!url) return;
		if (data.known_nodes[url]) return;
		data.known_nodes[url] = true;
		data.node_count += 1;
	}

	function check() {
		if (!data.processed_count) return;
		var missing = false;
		var missing_nodes = [];
		var all_count = 0;
		for (var item in data.known_nodes) {
			if (!data.known_nodes.hasOwnProperty(item)) continue;
			all_count += 1;
			if (!data.processed_nodes[item]) {
				missing_nodes.push(item);
				missing = true;
			}
		}

		
		if (!all_count) return;

		
		if (missing) return;

		
		if (!data.failed.length) {
			if (window.console) console.log(data.loaded.length + ' CDN assets loaded OK');
			reset();
			return;
		}

		
		var i;
		var j = data.failed.length;
		var failed = [];
		var failed_css = 0;
		var failed_js = 0;
		var node_name;

		for (i = 0; i < j; i += 1) {
			failed.push(getURL(data.failed[i].node));
			node_name = data.failed[i].node.nodeName.toLowerCase();
			if (node_name === 'link') failed_css += 1;
			if (node_name === 'script') failed_js += 1;
		}

		if (window.console && console.error) {
			console.error(data.failed.length + ' CDN assets failed to load: ' + failed.join(', '));
		}

		if (window.TSBeacon) {
			if (failed_css) window.TSBeacon('cdn_load_tracking_failed_css', failed_css);
			if (failed_js) window.TSBeacon('cdn_load_tracking_failed_js', failed_js);
		}

		if (isReloading) {
			if (window.console) {
				console.warn(
					'[RELOAD] Detected more issues loading assets, but we are already preparing to reload so no need to do anything',
				);
			}
			return;
		}

		const params = new URLSearchParams(location.search);
		let cdnFallbackCount = parseInt(params.get('cdn_fallback'), 10) || 0;
		cdnFallbackCount += 1;

		if (cdnFallbackCount > MAX_CDN_FALLBACK) {
			if (window.console) {
				console.warn("[RELOAD] Hit maximum reload attempts, won't try reloading anymore");
			}
			return;
		}

		isReloading = true;
		params.set('cdn_fallback', cdnFallbackCount.toString());

		
		const newSearch = params.toString();
		if (window.console) console.warn(`[RELOAD] Reloading client with URL: ${newSearch}`);

		
		let timeout;
		const reload = () => {
			console.warn(`[RELOAD] Reloading client with URL: ${newSearch}`);
			window.clearTimeout(timeout);
			window.location.search = newSearch;
		};
		timeout = window.setTimeout(reload.bind(this), 5000);

		const preReloadPromises = [];

		
		
		
		
		if (window.desktop?.stats?.clearCache) {
			preReloadPromises.push(
				new Promise((resolve) => {
					try {
						console.warn('[RELOAD] Attempting to clear desktop cache');
						window.desktop.stats.clearCache().then(() => {
							console.warn('[RELOAD] Successfully cleared desktop cache');
							resolve();
						});
					} catch (e) {
						console.warn(
							`[RELOAD] Error clearing desktop cache: ${!!e && typeof e === 'object' && 'message' in e ? e.message : 'Unknown error'}`,
						);
						resolve();
					}
				}),
			);
		}

		Promise.all(preReloadPromises).then(reload);
	}

	reset();

	window._cdn = {
		check: check,
		count: count,
		data: data,
		ok: ok,
		failed: failed,
		scanPageAssets: scanPageAssets,
	};
})();
</script><link href="https://a.slack-edge.com/bv1-13-br/marketing-style-onetrust-banner.1d1c7b4a0d01fa8e.min.css" rel="stylesheet" type="text/css" onload="window._cdn ? _cdn.ok(this, arguments) : null" onerror="window._cdn ? _cdn.failed(this, arguments) : null" crossorigin="anonymous"><link href="https://a.slack-edge.com/bv1-13-br/legacy-style-libs-lato-2-compressed.f97f6fd36fc9636d.min.css" rel="stylesheet" type="text/css" onload="window._cdn ? _cdn.ok(this, arguments) : null" onerror="window._cdn ? _cdn.failed(this, arguments) : null" crossorigin="anonymous"><link href="https://a.slack-edge.com/bv1-13-br/marketing-style-generic-typography-avantgarde.c934e77ecdecd549.min.css" rel="stylesheet" type="text/css" onload="window._cdn ? _cdn.ok(this, arguments) : null" onerror="window._cdn ? _cdn.failed(this, arguments) : null" crossorigin="anonymous"><link href="https://a.slack-edge.com/bv1-13-br/marketing-style-generic-typography-sfsans.c0d85c66f6c49bc2.min.css" rel="stylesheet" type="text/css" onload="window._cdn ? _cdn.ok(this, arguments) : null" onerror="window._cdn ? _cdn.failed(this, arguments) : null" crossorigin="anonymous"><link rel="canonical" href="https://slack.com">

<link rel="alternate" hreflang="en-us" href="https://slack.com">

<link rel="alternate" hreflang="en-au" href="https://slack.com/intl/en-au">

<link rel="alternate" hreflang="en-gb" href="https://slack.com/intl/en-gb">

<link rel="alternate" hreflang="en-in" href="https://slack.com/intl/en-in">

<link rel="alternate" hreflang="fr-ca" href="https://slack.com/intl/fr-ca">

<link rel="alternate" hreflang="fr-fr" href="https://slack.com/intl/fr-fr">

<link rel="alternate" hreflang="de-de" href="https://slack.com/intl/de-de">

<link rel="alternate" hreflang="es-es" href="https://slack.com/intl/es-es">

<link rel="alternate" hreflang="es" href="https://slack.com/intl/es-la">

<link rel="alternate" hreflang="ja-jp" href="https://slack.com/intl/ja-jp">

<link rel="alternate" hreflang="pt-br" href="https://slack.com/intl/pt-br">

<link rel="alternate" hreflang="ko-kr" href="https://slack.com/intl/ko-kr">

<link rel="alternate" hreflang="it-it" href="https://slack.com/intl/it-it">

<link rel="alternate" hreflang="zh-cn" href="https://slack.com/intl/zh-cn">

<link rel="alternate" hreflang="zh-tw" href="https://slack.com/intl/zh-tw">

<link rel="alternate" hreflang="x-default" href="https://slack.com">

<script>window.ts_endpoint_url = "https:\/\/slack.com\/beacon\/timing";(function(e) {
	var n=Date.now?Date.now():+new Date,r=e.performance||{},t=[],a={},i=function(e,n){for(var r=0,a=t.length,i=[];a>r;r++)t[r][e]==n&&i.push(t[r]);return i},o=function(e,n){for(var r,a=t.length;a--;)r=t[a],r.entryType!=e||void 0!==n&&r.name!=n||t.splice(a,1)};r.now||(r.now=r.webkitNow||r.mozNow||r.msNow||function(){return(Date.now?Date.now():+new Date)-n}),r.mark||(r.mark=r.webkitMark||function(e){var n={name:e,entryType:"mark",startTime:r.now(),duration:0};t.push(n),a[e]=n}),r.measure||(r.measure=r.webkitMeasure||function(e,n,r){n=a[n].startTime,r=a[r].startTime,t.push({name:e,entryType:"measure",startTime:n,duration:r-n})}),r.getEntriesByType||(r.getEntriesByType=r.webkitGetEntriesByType||function(e){return i("entryType",e)}),r.getEntriesByName||(r.getEntriesByName=r.webkitGetEntriesByName||function(e){return i("name",e)}),r.clearMarks||(r.clearMarks=r.webkitClearMarks||function(e){o("mark",e)}),r.clearMeasures||(r.clearMeasures=r.webkitClearMeasures||function(e){o("measure",e)}),e.performance=r,"function"==typeof define&&(define.amd||define.ajs)&&define("performance",[],function(){return r}) // eslint-disable-line
})(window);</script><script>

(function () {
	
	window.TSMark = function (mark_label) {
		if (!window.performance || !window.performance.mark) return;
		performance.mark(mark_label);
	};
	window.TSMark('start_load');

	
	window.TSMeasureAndBeacon = function (measure_label, start_mark_label) {
		if (!window.performance || !window.performance.mark || !window.performance.measure) {
			return;
		}

		performance.mark(start_mark_label + '_end');

		try {
			performance.measure(measure_label, start_mark_label, start_mark_label + '_end');
			window.TSBeacon(measure_label, performance.getEntriesByName(measure_label)[0].duration);
		} catch (e) {
			
		}
	};

	
	if ('sendBeacon' in navigator) {
		window.TSBeacon = function (label, value) {
			var endpoint_url = window.ts_endpoint_url || 'https://slack.com/beacon/timing';
			navigator.sendBeacon(
				endpoint_url + '?data=' + encodeURIComponent(label + ':' + value),
				'',
			);
		};
	} else {
		window.TSBeacon = function (label, value) {
			var endpoint_url = window.ts_endpoint_url || 'https://slack.com/beacon/timing';
			new Image().src = endpoint_url + '?data=' + encodeURIComponent(label + ':' + value);
		};
	}
})();
</script><script>window.TSMark('step_load');</script><script>
(function () {
	function throttle(callback, intervalMs) {
		var wait = false;

		return function () {
			if (!wait) {
				callback.apply(null, arguments);
				wait = true;
				setTimeout(function () {
					wait = false;
				}, intervalMs);
			}
		};
	}

	function getGenericLogger() {
		return {
			warn: (msg) => {
				
				if (window.console && console.warn) return console.warn(msg);
			},
			error: (msg) => {
				if (!msg) return;

				if (window.TSBeacon) return window.TSBeacon(msg, 1);

				
				if (window.console && console.warn) return console.warn(msg);
			},
		};
	}

	function globalErrorHandler(evt) {
		if (!evt) return;

		
		var details = '';

		var node = evt.srcElement || evt.target;

		var genericLogger = getGenericLogger();

		
		
		
		
		if (node && node.nodeName) {
			var nodeSrc = '';
			if (node.nodeName === 'SCRIPT') {
				nodeSrc = node.src || 'unknown';
				var errorType = evt.type || 'error';

				
				details = `[${errorType}] from script at ${nodeSrc} (failed to load?)`;
			} else if (node.nodeName === 'IMG') {
				nodeSrc = node.src || node.currentSrc;

				genericLogger.warn(`<img> fired error with url = ${nodeSrc}`);
				return;
			}
		}

		
		if (evt.error && evt.error.stack) {
			details += ` ${evt.error.stack}`;
		}

		if (evt.filename) {
			
			var fileName = evt.filename;
			var lineNo = evt.lineno;
			var colNo = evt.colno;

			details += ` from ${fileName}`;

			
			if (lineNo) {
				details += ` @ line ${lineNo}, col ${colNo}`;
			}
		}

		var msg;

		
		if (evt.error && evt.error.stack) {
			
			msg = details;
		} else {
			
			msg = `${evt.message || ''} ${details}`;
		}

		
		if (msg && msg.replace) msg = msg.replace(/\s+/g, ' ').trim();

		if (!msg || !msg.length) {
			if (node) {
				var nodeName = node.nodeName || 'unknown';

				
				
				
				if (nodeName === 'VIDEO') {
					return;
				}

				msg = `error event from node of ${nodeName}, no message provided?`;
			} else {
				msg = 'error event fired, no relevant message or node found';
			}
		}

		var logPrefix = 'ERROR caught in js/inline/register_global_error_handler';

		msg = `${logPrefix} - ${msg}`;

		genericLogger.error(msg);
	}

	
	
	
	var capture = true;

	
	var throttledHandler = throttle(globalErrorHandler, 10000);

	window.addEventListener('error', throttledHandler, capture);
})();
</script><script type="text/javascript" crossorigin="anonymous" src="https://a.slack-edge.com/bv1-13-br/manifest.35bb998c714c23bd.primer.min.js" onload="window._cdn ? _cdn.ok(this, arguments) : null" onerror="window._cdn ? _cdn.failed(this, arguments) : null"></script><noscript><meta http-equiv="refresh" content="0; URL=/?redir=%2Ffiles-pri%2FT27SFGS2W-F0BJ3RA6F9A%2Fproject-analysis-2026-07.md&amp;nojsmode=1"></noscript><script type="text/javascript">var safe_hosts = ["app.optimizely.com","tinyspeck.dev.slack.com","houston-dev.tinyspeck.com","houston.tinyspeck.com"];

if (self !== top && safe_hosts.indexOf(top.location.host) === -1) {
	window.document.write(
		'\u003Cstyle>body * {display:none !important;}\u003C/style>\u003Ca href="#" onclick=' +
			'"top.location.href=window.location.href" style="display:block !important;padding:10px">Go to Slack.com\u003C/a>'
	);
}

(function() {
	var timer;
	if (self !== top && safe_hosts.indexOf(top.location.host) === -1) {
		timer = window.setInterval(function() {
			if (window) {
				try {
					var pageEl = document.getElementById('page');
					var clientEl = document.getElementById('client-ui');
					var sectionEls = document.querySelectorAll('nav, header, section');

					pageEl.parentNode.removeChild(pageEl);
					clientEl.parentNode.removeChild(clientEl);
					for (var i = 0; i < sectionEls.length; i++) {
						sectionEls[i].parentNode.removeChild(sectionEls[i]);
					}
					window.TS = null;
					window.TD = null;
					window.clearInterval(timer);
				} catch (e) {}
			}
		}, 200);
	}
})();</script><script>window.GA = window.GA || {};
window.GA.boot_data = window.GA.boot_data || {};
GA.boot_data.xhp = true;
GA.boot_data.version_uid = "47015550507b36c032d4d75a8915ea8fec99bfd0";
GA.boot_data.environment = "prod";
GA.boot_data.abs_root_url = "https:\/\/slack.com\/";
GA.boot_data.document_referrer = "";

GA.boot_data.anonymous_visitor = false;
GA.boot_data.beacon_timing_url = "https:\/\/slack.com\/beacon\/timing";
GA.boot_data.referral_code = "";
GA.boot_data.auth_cookie_domain = ".slack.com";

GA.boot_data.geo = {"ip":"47.164.81.246","country":"US","is_in_european_union":false,"region":"CT","city":"Middlebury","zip":"06762","lat":41.5279,"lon":-73.1276,"metro":533,"country_label":"United States","region_label":"Connecticut","country3":"USA","continent":"NA","isp":"Frontier Communications"};
GA.boot_data.geocode = "en-us";
GA.boot_data.intl_prefix = "";
GA.boot_data.request_uri = "\/?redir=%2Ffiles-pri%2FT27SFGS2W-F0BJ3RA6F9A%2Fproject-analysis-2026-07.md";
GA.boot_data.canonical_web_url = "https:\/\/slack.com\/";
GA.boot_data.i18n_locale = "en-US";
GA.boot_data.geo_root_url = "https:\/\/slack.com\/";

GA.boot_data.is_usa = true;
GA.boot_data.is_spain = false;
GA.boot_data.is_germany = false;
GA.boot_data.is_france = false;
GA.boot_data.is_japan = false;
GA.boot_data.is_europe = false;

GA.boot_data.is_latam = false;
GA.boot_data.is_brazil = false;
GA.boot_data.is_india = false;
GA.boot_data.is_uk = false;

GA.boot_data.is_english = true;
GA.boot_data.is_spanish = false;
GA.boot_data.is_german = false;
GA.boot_data.is_french = false;
GA.boot_data.is_japanese = false;
GA.boot_data.is_portuguese = false;

GA.boot_data.job_board_token = "slack";
GA.boot_data.zd_locale = "en-us";
</script><meta name="facebook-domain-verification" content="chiwsajpoybn2cnqyj9w8mvrey56m0"><script type="text/javascript">
window.dataLayer = window.dataLayer || [];
function gtag(){window.dataLayer.push(arguments);}

function loadGTM() {
	window.dataLayer.push({
		'gtm.start': Date.now(),
		'event': 'gtm.js',
		'AnalyticsActiveGroups': window.OnetrustActiveGroups || ',1',
		'policy_ga_only': true,
	});
	var firstScript = document.getElementsByTagName('script')[0];
	var thisScript = document.createElement('script');
	thisScript.async = true;
	thisScript.src = '//www.googletagmanager.com/gtm.js?id=GTM-KH2LPK';
	firstScript.parentNode.insertBefore(thisScript, firstScript);
}


</script><script type="text/javascript">
document.addEventListener("DOMContentLoaded", function(e) {
	var gtmDataLayer = window.dataLayer || [];
	var gtmTags = document.querySelectorAll('*[data-gtm-click]');
	var gtmClickHandler = function(c) {
		var gtm_events = this.getAttribute('data-gtm-click');
		if (!gtm_events) return;
		var gtm_events_arr = gtm_events.split(",");
		for(var e=0; e < gtm_events_arr.length; e++) {
			var ev = gtm_events_arr[e].trim();
			gtmDataLayer.push({ 'event': ev });
		}
	};
	for(var g=0; g < gtmTags.length; g++){
		var elem = gtmTags[g];
		elem.addEventListener('click', gtmClickHandler);
	}
});
</script><script type="text/javascript">
(function(e,c,b,f,d,g,a){e.SlackBeaconObject=d;
e[d]=e[d]||function(){(e[d].q=e[d].q||[]).push([1*new Date(),arguments])};
e[d].l=1*new Date();g=c.createElement(b);a=c.getElementsByTagName(b)[0];
g.async=1;g.src=f;a.parentNode.insertBefore(g,a)
})(window,document,"script","https://a.slack-edge.com/bv1-13-br/slack_beacon.1de5853dee52ce81.min.js","sb");
window.sb('set', 'token', '3307f436963e02d4f9eb85ce5159744c');
window.sb('track', 'pageview');
</script><script src="https://cdn.cookielaw.org/scripttemplates/otSDKStub.js" data-document-language="true" data-domain-script="3bcd90cf-1e32-46d7-adbd-634f66b65b7d"></script><script>window.OneTrustLoaded = true;</script><script>
window.dataLayer = window.dataLayer || [];

function afterConsentScripts() {
	window.TD.analytics.doPush();

	const bottomBannerEl = document.querySelector('.c-announcement-banner-bottom');
	if (bottomBannerEl !== null) {
		bottomBannerEl.classList.remove('c-announcement-banner-bottom-invisible');
	}
}



function toNumberSet(value) {
    let arr;
    arr = value.split(',');

    let set = {};
    for (let i = 1; i < arr.length; i++) {
      let n = parseInt(String(arr[i]).trim(), 10);
      if (!isNaN(n)) set[n] = true;
    }
    return set;
  }

  function grantedIfBoth(policySet, activeSet, id) {
    return !!(policySet[id] && activeSet[id]);
  }

  function updateGoogleConsentFromOneTrust(pagePolicy) {
		let policySet = toNumberSet(pagePolicy);
		let activeSet = toNumberSet(window.OptanonActiveGroups || window.OnetrustActiveGroups || ',1');
		let functionalGranted  = grantedIfBoth(policySet, activeSet, 3); // category 3
		let adsGranted         = grantedIfBoth(policySet, activeSet, 4); // category 4

		gtag('consent', "default", {"ad_storage":"denied","ad_user_data":"denied","ad_personalization":"denied","personalization_storage":"denied","analytics_storage":"denied","functionality_storage":"denied","security_storage":"denied","wait_for_update":1000});
		gtag('consent', 'update', {
			ad_storage: adsGranted ? 'granted' : 'denied',
			personalization_storage: adsGranted ? 'granted' : 'denied',
			ad_user_data: adsGranted ? 'granted' : 'denied',
			ad_personalization: adsGranted ? 'granted' : 'denied',

			security_storage: 'denied',
			analytics_storage:      functionalGranted  ? 'granted' : 'denied',
			functionality_storage: 'denied',
		});
	}


let initOneTrustReady = false;
function OptanonWrapper() {
updateGoogleConsentFromOneTrust(',1,2,');
	if (!initOneTrustReady) {
		document.dispatchEvent(new CustomEvent('OneTrustLoaded'));
		window.dataLayer.push({'event': 'OneTrustReady'});
		document.dispatchEvent(new CustomEvent('OneTrustReady'));
		// this will error in dev, add ?analytics=1 to url to include analytics and make this fn available
		loadGTM();
		initOneTrustReady = true;
	} else {
		window.dataLayer = window.dataLayer || [];
		window.dataLayer.push({
			'event': 'AnalyticsActiveGroupsUpdated',
			'AnalyticsActiveGroups': window.OptanonActiveGroups || window.OnetrustActiveGroups || ',1'
		});
	}

	if (!Optanon.GetDomainData().ShowAlertNotice || false) {
		afterConsentScripts();
	} else {
		document.querySelector('#onetrust-accept-btn-handler').focus()
	}
	Optanon.OnConsentChanged(function() {
		afterConsentScripts();
	});
}</script><script type="text/javascript">var TS_last_log_date = null;
var TSMakeLogDate = function() {
	var date = new Date();

	var y = date.getFullYear();
	var mo = date.getMonth()+1;
	var d = date.getDate();

	var time = {
	  h: date.getHours(),
	  mi: date.getMinutes(),
	  s: date.getSeconds(),
	  ms: date.getMilliseconds()
	};

	Object.keys(time).map(function(moment, index) {
		if (moment == 'ms') {
			if (time[moment] < 10) {
				time[moment] = time[moment]+'00';
			} else if (time[moment] < 100) {
				time[moment] = time[moment]+'0';
			}
		} else if (time[moment] < 10) {
			time[moment] = '0' + time[moment];
		}
	});

	var str = y + '/' + mo + '/' + d + ' ' + time.h + ':' + time.mi + ':' + time.s + '.' + time.ms;
	if (TS_last_log_date) {
		var diff = date-TS_last_log_date;
		//str+= ' ('+diff+'ms)';
	}
	TS_last_log_date = date;
	return str+' ';
}

var parseDeepLinkRequest = function(code) {
	var m = code.match(/"id":"([CDG][A-Z0-9]{8,})"/);
	var id = m ? m[1] : null;

	m = code.match(/"team":"(T[A-Z0-9]{8,})"/);
	var team = m ? m[1] : null;

	m = code.match(/"message":"([0-9]+\.[0-9]+)"/);
	var message = m ? m[1] : null;

	return { id: id, team: team, message: message };
}

if ('rendererEvalAsync' in window) {
	var origRendererEvalAsync = window.rendererEvalAsync;
	window.rendererEvalAsync = function(blob) {
		try {
			var data = JSON.parse(decodeURIComponent(atob(blob)));
			if (data.code.match(/handleDeepLink/)) {
				var request = parseDeepLinkRequest(data.code);
				if (!request.id || !request.team || !request.message) return;

				request.cmd = 'channel';
				TSSSB.handleDeepLinkWithArgs(JSON.stringify(request));
				return;
			} else {
				origRendererEvalAsync(blob);
			}
		} catch (e) {
		}
	}
}</script><script type="text/javascript">var TSSSB = {
	call: function() {
		return false;
	}
};</script><title>Sign in to IBM | Slack</title><meta name="referrer" content="no-referrer"><meta name="author" content="Slack"><meta name="description" content="Find and sign in to your Slack workspace."><meta name="keywords" content=""><meta name="robots" content="noindex, nofollow"></head><body class="full_height"><main id="main"><div id="page_contents"><div id="props_node" data-props="{&quot;recaptchaSitekey&quot;:&quot;6LcRpcIrAAAAAFAe8rv1DygnSMeBZNtDL8rhu2Ze&quot;,&quot;crumbValue&quot;:&quot;s-1784560353-4448f1911d6c6ea7b16b016bc60b01f67f39233020285db8c30a79d2a1fd31ce-\u2603&quot;,&quot;isSSB&quot;:false,&quot;isSSBBrowserSignin&quot;:false,&quot;isSSBSonicBrowserSignin&quot;:false,&quot;redirectURL&quot;:&quot;%2Ffiles-pri%2FT27SFGS2W-F0BJ3RA6F9A%2Fproject-analysis-2026-07.md&quot;,&quot;noSSO&quot;:false,&quot;isSSORequiredForAll&quot;:false,&quot;isSSOOptional&quot;:true,&quot;isSSORequiredForOwners&quot;:false,&quot;isTrailheadPlayground&quot;:false,&quot;forceEmailPasswordLogin&quot;:false,&quot;samlProviderLabel&quot;:&quot;IBM W3id&quot;,&quot;samlLogooutMayBeRequired&quot;:false,&quot;idpConfigDetails&quot;:{&quot;361c0eb7b53b58676bc76d1f53582b859ebeba937af852753da277428f986208&quot;:&quot;IBM w3id&quot;},&quot;ssoAction&quot;:&quot;login&quot;,&quot;logo&quot;:{},&quot;orgUrl&quot;:&quot;https:\/\/ibm.enterprise.slack.com\/&quot;,&quot;enterprise&quot;:{&quot;name&quot;:&quot;IBM&quot;,&quot;domain&quot;:&quot;ibm&quot;},&quot;SSBVersion&quot;:&quot;&quot;,&quot;deeplinkSchemes&quot;:{&quot;slack&quot;:&quot;SLACK&quot;,&quot;slackmdm&quot;:&quot;SLACK_MDM&quot;,&quot;slackintune&quot;:&quot;SLACK_INTUNE&quot;,&quot;slackdebug&quot;:&quot;SLACK_DEBUG&quot;,&quot;slackbeta&quot;:&quot;SLACK_BETA&quot;,&quot;slackprototype&quot;:&quot;SLACK_PROTOTYPE&quot;,&quot;slackdeveloper&quot;:&quot;SLACK_DEVELOPER&quot;},&quot;emailValue&quot;:null,&quot;experimentAdminEmailVerificationV1GroupOn&quot;:true,&quot;usernameSigninPermitted&quot;:false,&quot;experimentUsernameSigninGroupOn&quot;:false,&quot;errors&quot;:{&quot;isNoMatch&quot;:false,&quot;isRateLimited&quot;:false,&quot;isMissing&quot;:false,&quot;isIncorrectUser&quot;:false,&quot;isDeleted&quot;:false,&quot;isIncorrectPassword&quot;:false,&quot;isEmailAssociatedWithDeactivatedAccount&quot;:false,&quot;isSSOIncorrectlyFormatted&quot;:false,&quot;isTwoFactorWrong&quot;:false,&quot;isSMSRateLimited&quot;:false,&quot;isIncorrectTwoFactorState&quot;:false,&quot;isNoSSOOwner&quot;:false,&quot;isNoSSORa&quot;:false,&quot;hasSSORequiredForOwnersError&quot;:false,&quot;isNotConfirmed&quot;:false},&quot;isIOS&quot;:false}"></div></div></main><script type="text/javascript">
/**
 * A placeholder function that the build script uses to
 * replace file paths with their CDN versions.
 *
 * @param {String} file_path - File path
 * @returns {String}
 */
function vvv(file_path) {
		 var vvv_warning = 'You cannot use vvv on dynamic values. Please make sure you only pass in static file paths.'; if (window.TS && window.TS.warn) { window.TS.warn(vvv_warning); } else { console.warn(vvv_warning); } 
	return file_path;
}

var cdn_url = "https:\/\/a.slack-edge.com";
var vvv_abs_url = "https:\/\/slack.com\/";
var inc_js_setup_data = {
	emoji_sheets: {
		apple: 'https://a.slack-edge.com/80588/img/emoji_2017_12_06/sheet_apple_64_indexed_256.png',
		google: 'https://a.slack-edge.com/80588/img/emoji_2017_12_06/sheet_google_64_indexed_256.png',
	},
};
</script><script nonce="" type="text/javascript">	// common boot_data
	var boot_data = {"cdn":{"edges":["https:\/\/a.slack-edge.com\/","https:\/\/b.slack-edge.com\/","https:\/\/a.slack-edge.com\/"],"avatars":"https:\/\/ca.slack-edge.com\/","downloads":"https:\/\/downloads.slack-edge.com\/","files":"https:\/\/slack-files.com\/"},"feature_builder_story_step":false,"feature_olug_remove_required_workspace_setting":false,"feature_file_threads":true,"feature_broadcast_indicator":true,"feature_sonic_emoji":true,"feature_attachments_inline":false,"feature_desktop_symptom_events":false,"feature_gdpr_user_join_tos":true,"feature_user_invite_tos_april_2018":true,"feature_channel_mgmt_message_count":false,"feature_channel_exports":false,"feature_allow_intra_word_formatting":true,"feature_slim_scrollbar":false,"feature_edge_upload_proxy_check":false,"feature_set_tz_automatically":true,"feature_attachments_v2":true,"feature_beacon_js_errors":false,"feature_user_app_disable_speed_bump":false,"feature_apps_manage_permissions_scope_changes":true,"feature_ia_member_profile":true,"feature_desktop_reload_on_generic_error":true,"feature_desktop_extend_app_menu":true,"feature_desktop_restart_service_worker":false,"feature_wta_stop_creation":true,"feature_admin_email_change_confirm":false,"feature_improved_email_rendering":true,"feature_recent_desktop_files":true,"feature_cea_allowlist_changes":false,"feature_cea_channel_management":true,"feature_cea_admin_controls":true,"feature_cea_allowlist_changes_plus":true,"feature_ia_layout":true,"feature_threaded_call_block":true,"feature_enterprise_mobile_device_check":true,"feature_trace_jq_init":true,"feature_seven_days_email_update":true,"feature_channel_sections":true,"feature_show_email_forwarded_by":false,"feature_mpdm_audience_expansion":true,"feature_remove_email_preview_link":true,"feature_desktop_enable_tslog":false,"feature_email_determine_charset":true,"feature_no_deprecation_in_updater":false,"feature_pea_domain_allowlist":true,"feature_composer_auth_admin":false,"feature_browser_deprecation_june_2026":true,"experiment_assignments":{"marketing_seo_redirects_geocode":{"experiment_id":"11619720470308","type":"visitor","group":"on","schedule_ts":1784330939,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_user_intent_v":{"experiment_id":"11563365736258","type":"visitor","group":"on","trigger":"hash_visitor","schedule_ts":1784317489,"log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"paid_slackbot_updates_i18n":{"experiment_id":"11226555236836","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"cust_acq_slackbot_2_hp":{"experiment_id":"10788895416320","type":"visitor","group":"on","schedule_ts":1774979589,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"wmp_media_poster_generation":{"experiment_id":"11393652996182","type":"visitor","group":"on","schedule_ts":1784045446,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"wmp_media_metadata_extraction":{"experiment_id":"11351981406580","type":"visitor","group":"on","schedule_ts":1784045375,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"cust_acq_hp_hero_uk":{"experiment_id":"11292395359159","type":"visitor","group":"control","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"slackbot_mcp_banner_sfdc":{"experiment_id":"11530420886768","type":"visitor","group":"on","schedule_ts":1783511928,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"gx_paid_lp_updates":{"experiment_id":"11452100040051","type":"visitor","group":"on","schedule_ts":1783347976,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"lower_daily_confirmation_code_cap_for_no_account_emails":{"experiment_id":"11483176496434","type":"visitor","group":"on","schedule_ts":1782936375,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"slackbot_mcp_june18":{"experiment_id":"11385514779521","type":"visitor","group":"on","schedule_ts":1781794927,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"dreamforce_2026_banners":{"experiment_id":"11313036219216","type":"visitor","group":"on","schedule_ts":1782543386,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"utility_nav_q2fy27":{"experiment_id":"11400325857350","type":"visitor","group":"treatment","trigger":"hash_visitor","schedule_ts":1781903292,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"marketplace_nav_cta_q2fy27":{"experiment_id":"11373512094102","type":"visitor","group":"control","trigger":"hash_visitor","schedule_ts":1781903292,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"teams_alternatives_card_i18n":{"experiment_id":"11398705121860","type":"visitor","group":"on","schedule_ts":1782164804,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"slackbot_mcp_promo_banner":{"experiment_id":"11387300689841","type":"visitor","group":"on","schedule_ts":1781794787,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketplace_aa_v2_q2fy27":{"experiment_id":"11378502141253","type":"visitor","group":"control","trigger":"hash_visitor","schedule_ts":1781791220,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"integrations_ctas_q2fy27":{"experiment_id":"11325120987491","type":"visitor","group":"treatment_a","trigger":"hash_visitor","schedule_ts":1781791368,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"gx_sign_up_with_ms":{"experiment_id":"11389090204100","type":"visitor","group":"control","trigger":"hash_visitor","schedule_ts":1781726937,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"loggedin_hp_bus_ent_q2fy27":{"experiment_id":"11186772029601","type":"visitor","group":"treatment","trigger":"hash_visitor","schedule_ts":1781213897,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"marketplace_aa_test_q2fy27":{"experiment_id":"11217928527110","type":"visitor","group":"control","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"get_started_pricing":{"experiment_id":"10957934732291","type":"visitor","group":"treatment","trigger":"hash_visitor","schedule_ts":1776811725,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"get_started_pricing_hp":{"experiment_id":"10916177566887","type":"visitor","group":"treatment","trigger":"hash_visitor","schedule_ts":1776366250,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"sc_invite_reminder_show_email":{"experiment_id":"11236231681653","type":"visitor","group":"on","schedule_ts":1780948388,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"cust_acq_hp_trust_exp_fr":{"experiment_id":"10766277405554","type":"visitor","group":"control","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"cust_acq_hp_trust_exp_de":{"experiment_id":"10762325425845","type":"visitor","group":"control","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"cust_acq_hp_trust_exp_uk":{"experiment_id":"10765605003858","type":"visitor","group":"control","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"mobile_features_fyp_q2fy27":{"experiment_id":"11266096482432","type":"visitor","group":"treatment","trigger":"hash_visitor","schedule_ts":1780335361,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"marketplace_switcher_jp":{"experiment_id":"11141013701895","type":"visitor","group":"on","schedule_ts":1780590735,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"atlas_redirect_modular_page":{"experiment_id":"10773103464453","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"modular_pages_redirect_batch":{"experiment_id":"11239018110726","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"partner_program_nav_i18n":{"experiment_id":"11153051411296","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"slack_vs_teams_faq_update_i18n":{"experiment_id":"11127693812004","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"zigzag_stat_source_i18n":{"experiment_id":"10995571682100","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"team_chat_faq_update_i18n":{"experiment_id":"10927217140004","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"cust_acq_get_started_free_copy":{"experiment_id":"10501310080289","type":"visitor","group":"control","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"marketing_agent_dotcom":{"experiment_id":"10676597077616","type":"visitor","group":"on","schedule_ts":1779904715,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"mobile_email_invite_verification":{"experiment_id":"9826078477091","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"cust_acq_featuring_innovation_hp":{"experiment_id":"11143861174212","type":"visitor","group":"on","schedule_ts":1779808976,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"pricing_inproduct_team_creation":{"experiment_id":"11022054155572","type":"visitor","group":"on","schedule_ts":1779117734,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"plan_finder_static":{"experiment_id":"10916666221635","type":"visitor","group":"treatment","trigger":"hash_visitor","schedule_ts":1778281818,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"fedramp_changes_in_compliance_page":{"experiment_id":"11122569374100","type":"visitor","group":"on","schedule_ts":1778778139,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"mobile_pricing_logos_messaging":{"experiment_id":"10711954997332","type":"visitor","group":"control","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"sfdc_chat_promise_override":{"experiment_id":"11011087201686","type":"visitor","group":"on","schedule_ts":1778602352,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"today_beta":{"experiment_id":"10964821051571","type":"visitor","group":"on","schedule_ts":1778093005,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_agent_dotcom_i18n":{"experiment_id":"10664826465156","type":"visitor","group":"on","schedule_ts":1778260800,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_mris_to_mars":{"experiment_id":"10731019339431","type":"visitor","group":"on","schedule_ts":1778260367,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"resources_cvr_benchmarking":{"experiment_id":"10749687846656","type":"visitor","group":"control","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"marketing_index_now":{"experiment_id":"10997629882579","type":"visitor","group":"on","schedule_ts":1777355776,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"gx_signup_security_message":{"experiment_id":"10726565368966","type":"visitor","group":"control","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"phase_2_slack_teams_compete":{"experiment_id":"10775924967558","type":"visitor","group":"on","schedule_ts":1776870065,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"social_team_segmentation_visitor_gate":{"experiment_id":"10632194923811","type":"visitor","group":"off","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"get_started_revamp_existing_users_v2":{"experiment_id":"10680592142133","type":"visitor","group":"treatment","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"translation_get_started_workspace_aria_labels":{"experiment_id":"10867628810625","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"nav_unify_slack_vstr":{"experiment_id":"10465017661201","type":"visitor","group":"on","schedule_ts":1776193610,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"hide_all_webapp_banners":{"experiment_id":"10872013119254","type":"visitor","group":"on","schedule_ts":1776183615,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"activate_browser_deprecation_warning_april_2026":{"experiment_id":"10869856526080","type":"visitor","group":"on","schedule_ts":1775509125,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"slack_help_center_site_redesign":{"experiment_id":"10143993414212","type":"visitor","group":"on","schedule_ts":1775578665,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"browser_deprecation_red_banner_april_2026":{"experiment_id":"10315184320450","type":"visitor","group":"on","schedule_ts":1775504851,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"blog_cta_template_category":{"experiment_id":"10775631316294","type":"visitor","group":"control","trigger":"hash_visitor","schedule_ts":1775233737,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"cust_acq_customer_mgmt":{"experiment_id":"10691683151460","type":"visitor","group":"on","schedule_ts":1774979590,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"blog_cvr_benchmarking":{"experiment_id":"10539334517286","type":"visitor","group":"control","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"activate_browser_deprecation_warning_march_2026":{"experiment_id":"10699599084948","type":"visitor","group":"on","schedule_ts":1773688562,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"ios_mobile_team_creation_revamp_v_1":{"experiment_id":"8923214535731","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"tl_slack_vs_teams":{"experiment_id":"10341080467637","type":"visitor","group":"on","schedule_ts":1772065039,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"hp_revamp_25_exp":{"experiment_id":"10173190914803","type":"visitor","group":"treatment","schedule_ts":1766512091,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"admin_email_verification_v1_translations":{"experiment_id":"9848465692741","type":"visitor","group":"on","schedule_ts":1770129550,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"mobile_web_optimizations":{"experiment_id":"10028552136000","type":"visitor","group":"treatment","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"mobile_web_optimizations_row":{"experiment_id":"10003559639028","type":"visitor","group":"treatment","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"activate_browser_deprecation_warning_february_2026":{"experiment_id":"10443474327988","type":"visitor","group":"on","schedule_ts":1770304755,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"cust_acq_roi":{"experiment_id":"10111064219827","type":"visitor","group":"on","schedule_ts":1770242336,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"cust_acq_january_feature_drop":{"experiment_id":"10377412751717","type":"visitor","group":"on","schedule_ts":1770056354,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"workflow_builder":{"experiment_id":"9193723819463","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"cust_acq_whats_new":{"experiment_id":"9944278749911","type":"visitor","group":"on","schedule_ts":1766434685,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"email_optin_for_invite_join":{"experiment_id":"10239697974452","type":"visitor","group":"on","schedule_ts":1768414705,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"email_tips_pref_set_during_sso":{"experiment_id":"10060663792534","type":"visitor","group":"on","schedule_ts":1767897725,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"teams_compete":{"experiment_id":"10025794984901","type":"visitor","group":"on","schedule_ts":1766095647,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"app_directory_connectors":{"experiment_id":"6144504493874","type":"visitor","group":"treatment","schedule_ts":1705354312,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"app_directory_connectors_collection":{"experiment_id":"6321714753558","type":"visitor","group":"on","schedule_ts":1705448247,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"mrbeast_campaign":{"experiment_id":"9803944014723","type":"visitor","group":"on","schedule_ts":1762545488,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"hp_dreamforce_2025":{"experiment_id":"9631955160816","type":"visitor","group":"on","schedule_ts":1760356527,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"revamp_translation":{"experiment_id":"9715852724503","type":"visitor","group":"on","schedule_ts":1761590850,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"make_captcha_mandatory":{"experiment_id":"9152757690695","type":"visitor","group":"on","schedule_ts":1756492106,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"slack_trials_tof":{"experiment_id":"9687474940791","type":"visitor","group":"aa_control","trigger":"hash_visitor","schedule_ts":1760545417,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"aswebauth_cookie_session":{"experiment_id":"7920012625699","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"enterprise_search_demo":{"experiment_id":"8892730774644","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"recaptcha_enterprise_migration":{"experiment_id":"9412496834887","type":"visitor","group":"on","schedule_ts":1758834250,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"stripe_us_address":{"experiment_id":"9575757500321","type":"visitor","group":"on","schedule_ts":1759256395,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_optin_via_geo_library":{"experiment_id":"9500088909685","type":"visitor","group":"on","schedule_ts":1758125993,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"optimize_get_started_flow":{"experiment_id":"9119755158055","type":"visitor","group":"treatment","schedule_ts":1751399210,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"agent_dotcom_help":{"experiment_id":"8150736357476","type":"visitor","group":"on","schedule_ts":1757725719,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"partners_contact":{"experiment_id":"9508808187494","type":"visitor","group":"on","schedule_ts":1757716577,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"email_optin_via_geo_library":{"experiment_id":"9482988226449","type":"visitor","group":"on","schedule_ts":1757370053,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"integrations_update":{"experiment_id":"9304450356548","type":"visitor","group":"on","schedule_ts":1755802338,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_hc_agent_send_ga":{"experiment_id":"9368939411444","type":"visitor","group":"on","schedule_ts":1756933151,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"sticky_ctas":{"experiment_id":"9347505043607","type":"visitor","group":"on","schedule_ts":1755638126,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"versionify_url_for_invites":{"experiment_id":"9239948574183","type":"visitor","group":"on","schedule_ts":1753809230,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_templates_hreflang":{"experiment_id":"9344139344375","type":"visitor","group":"on","schedule_ts":1755280514,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"phone_number_de":{"experiment_id":"7168806134229","type":"visitor","group":"treatment","schedule_ts":1716908444,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"phone_number_fr":{"experiment_id":"7157169096183","type":"visitor","group":"treatment","schedule_ts":1716912646,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"phone_number_au":{"experiment_id":"7168837725445","type":"visitor","group":"treatment","schedule_ts":1716912663,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"phone_number_br":{"experiment_id":"7256414077620","type":"visitor","group":"treatment","schedule_ts":1719244830,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"phone_number_it":{"experiment_id":"7250980705925","type":"visitor","group":"treatment","schedule_ts":1719253456,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"phone_number_es":{"experiment_id":"7266548128369","type":"visitor","group":"treatment","schedule_ts":1719253482,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"phone_number_ca":{"experiment_id":"7256320861108","type":"visitor","group":"treatment","schedule_ts":1718813429,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"phone_number_pt":{"experiment_id":"7276731000896","type":"visitor","group":"treatment","schedule_ts":1719253468,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"phone_number_ko":{"experiment_id":"7276620382240","type":"visitor","group":"treatment","schedule_ts":1719852648,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"phone_number_ie":{"experiment_id":"7441623906036","type":"visitor","group":"treatment","schedule_ts":1721414251,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"phone_number_jp":{"experiment_id":"7174178940996","type":"visitor","group":"treatment","schedule_ts":1716583378,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"agent_demo_contact":{"experiment_id":"9180003200743","type":"visitor","group":"on","schedule_ts":1753365260,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"partner_promos_pnp":{"experiment_id":"8997210765159","type":"visitor","group":"on","schedule_ts":1750106249,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"affiliates_pnp_update":{"experiment_id":"9089245874021","type":"visitor","group":"on","schedule_ts":1751484387,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"dreamforce_promo":{"experiment_id":"9163466061797","type":"visitor","group":"on","schedule_ts":1752590157,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"july1_launch":{"experiment_id":"9111164109201","type":"visitor","group":"on","schedule_ts":1751403911,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"miro_marketplace":{"experiment_id":"8704952792931","type":"visitor","group":"on","schedule_ts":1750982806,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"channels_update":{"experiment_id":"8954244938662","type":"visitor","group":"on","schedule_ts":1748951415,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"spam_email_recaptcha_v3":{"experiment_id":"8890538137939","type":"visitor","group":"on","schedule_ts":1749589661,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketplace_add2":{"experiment_id":"8251117941749","type":"visitor","group":"on","schedule_ts":1736280665,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_aff":{"experiment_id":"5743479690565","type":"visitor","group":"on","schedule_ts":1749577342,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"signin_design_refresh":{"experiment_id":"8656625636818","type":"visitor","group":"on","schedule_ts":1746462640,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"creator_landing_view_refactor":{"experiment_id":"8986548312550","type":"visitor","group":"on","schedule_ts":1749141715,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"innovations_lp":{"experiment_id":"8253871971107","type":"visitor","group":"on","schedule_ts":1746643793,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"enterprise_search_lp":{"experiment_id":"8787098737047","type":"visitor","group":"on","schedule_ts":1746641312,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"mris_extension":{"experiment_id":"4746206947365","type":"visitor","group":"on","schedule_ts":1706216371,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"on24_extension":{"experiment_id":"4772019824211","type":"visitor","group":"on","schedule_ts":1675891595,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"cust_acq_sfdc_single_chat":{"experiment_id":"5665898305074","type":"visitor","group":"on","schedule_ts":1712596116,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"downloads_launch":{"experiment_id":"8552687986532","type":"visitor","group":"on","schedule_ts":1741711036,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"activation_enterprise_signin_primer":{"experiment_id":"6443324713893","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"screen_text_2fa":{"experiment_id":"7846147603012","type":"visitor","group":"on","schedule_ts":1734375504,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketplace_add":{"experiment_id":"7940445156581","type":"visitor","group":"on","schedule_ts":1732060351,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_ad_app_store_urls":{"experiment_id":"7746105288676","type":"visitor","group":"on","schedule_ts":1726699830,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"search_zd_vs_solr":{"experiment_id":"1355709002145","type":"visitor","group":"control","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"apidocs_ad_unit_enable":{"experiment_id":"6141172005908","type":"visitor","group":"on","trigger":"hash_visitor","schedule_ts":1712177688,"log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"downloads_s2p_promo":{"experiment_id":"7132023966439","type":"visitor","group":"control","trigger":"hash_visitor","schedule_ts":1718307458,"log_exposures":true,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"paid_lp_expand":{"experiment_id":"7134287733637","type":"visitor","group":"treatment","schedule_ts":1717713269,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_live_chat_emea":{"experiment_id":"7226533858036","type":"visitor","group":"on","schedule_ts":1717629445,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"new_paid_lp":{"experiment_id":"6818768684695","type":"visitor","group":"treatment","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"slack_elevate_launch":{"experiment_id":"6966627699558","type":"visitor","group":"on","schedule_ts":1713959798,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_recaptcha_hc":{"experiment_id":"6963734115829","type":"visitor","group":"on","schedule_ts":1713301140,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_hc_flow_specifier":{"experiment_id":"6989238991504","type":"visitor","group":"on","schedule_ts":1713296815,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"anthony_test_visitor_1":{"experiment_id":"6823470010164","type":"visitor","group":"treatment","schedule_ts":1712613615,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_media_kit":{"experiment_id":"6696687337684","type":"visitor","group":"on","schedule_ts":1709232747,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"out_of_office_xmas_jp":{"experiment_id":"6296845198293","type":"visitor","group":"off","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"out_of_office_xmas":{"experiment_id":"6322553087328","type":"visitor","group":"off","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"marketing_hreflang_errors_fix":{"experiment_id":"6319747700807","type":"visitor","group":"on","schedule_ts":1702931766,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"new_gated_demo":{"experiment_id":"6171698537921","type":"visitor","group":"on","schedule_ts":1701209093,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"marketing_cj":{"experiment_id":"5820701519667","type":"visitor","group":"on","schedule_ts":1699033035,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"deny_russian_ip":{"experiment_id":"3201051153989","type":"visitor","group":"on","trigger":"finished","log_exposures":false,"exposure_id":"228fa2914d05bd5e768ceecff36e453a"},"swap_ukraine_logo_toggle":{"experiment_id":"5598910456034","type":"visitor","group":"on","schedule_ts":1689885040,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"slack_ie_address":{"experiment_id":"4857849748754","type":"visitor","group":"on","schedule_ts":1677793396,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"proj_brand_customer_stories_lp":{"experiment_id":"3448021380448","type":"visitor","group":"on","schedule_ts":1653596127,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false},"digital_first_lightning_strike_custacq":{"experiment_id":"2220660679364","type":"visitor","group":"on","schedule_ts":1625075563,"exposure_id":"228fa2914d05bd5e768ceecff36e453a","trigger":"launch_visitor","log_exposures":false}},"no_login":false,"feature_marketing_user_intent_v":true,"user_intent":{"session_id":"228fa2914d05bd5e768ceecff36e453a.1784560353","started_at":"2026-07-20T15:12:33Z","funnel_stage":"awareness","initial_intent":"brand_navigational","current_intent":"brand_navigational","tags":["brand_navigational"],"tag_confidence":{"brand_navigational":1},"page_count":1,"visited_pages":[{"url":"\/","tags":["brand_navigational"]}],"first_session":null,"previous_session":null,"total_sessions":1,"total_page_views":1,"first_seen_at":"2026-07-20T15:12:33Z"}};</script><script type="text/javascript" crossorigin="anonymous" src="https://a.slack-edge.com/bv1-13-br/primer-vendor.c57866f4a573cae5.primer.min.js" onload="window._cdn ? _cdn.ok(this, arguments) : null" onerror="window._cdn ? _cdn.failed(this, arguments) : null"></script><script type="text/javascript" crossorigin="anonymous" src="https://a.slack-edge.com/bv1-13-br/enterprise-signin-core.a4e42de4b453d6eb.primer.min.js" onload="window._cdn ? _cdn.ok(this, arguments) : null" onerror="window._cdn ? _cdn.failed(this, arguments) : null" data-critical></script><link href="https://a.slack-edge.com/bv1-13-br/enterprise-signin-core.131ef5334f892e01.primer.min.css" rel="stylesheet" type="text/css" onload="window._cdn ? _cdn.ok(this, arguments) : null" onerror="window._cdn ? _cdn.failed(this, arguments) : null" crossorigin="anonymous"><link href="https://a.slack-edge.com/bv1-13-br/rollup-style-slack-kit-base.ba47d7bff7c8b98e.min.css" rel="stylesheet" id="slack_kit_helpers" type="text/css" onload="window._cdn ? _cdn.ok(this, arguments) : null" onerror="window._cdn ? _cdn.failed(this, arguments) : null" crossorigin="anonymous"><link href="https://a.slack-edge.com/bv1-13-br/rollup-style-slack-kit-helpers.1f83a2fd6d2830f6.min.css" rel="stylesheet" id="slack_kit_helpers" type="text/css" onload="window._cdn ? _cdn.ok(this, arguments) : null" onerror="window._cdn ? _cdn.failed(this, arguments) : null" crossorigin="anonymous"><script>if (window._cdn) _cdn.scanPageAssets();</script>

<!-- slack-www-hhvm-main-iad-jtpb7nzfepj5/ 2026-07-20 08:12:33/ v47015550507b36c032d4d75a8915ea8fec99bfd0/ B:H -->

</body></html>