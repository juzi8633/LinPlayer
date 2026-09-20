// 由 tools/uibundle/build.mjs 生成,**不要手改**。改 tools/uibundle/src/*.js 后跑 `pnpm --dir tools/uibundle build`。
// 内含 Preact 10.28.4(MIT)+ preact/hooks,外加 LinPlayer 的最小 DOM 与 surface 渲染器。
;(function (g) {
function __lp_makeScope(document) {
var scope = {};
(function () {
!function(n,t){"object"==typeof exports&&"undefined"!=typeof module?t(exports):"function"==typeof define&&define.amd?define(["exports"],t):t((n||self).preact={})}(this,function(n){var t,i,e,o,r,f,u,c,s,a,h,p,l,y="http://www.w3.org/2000/svg",v="http://www.w3.org/1999/xhtml",d=null,w=void 0,g={},_=[],m=/acit|ex(?:s|g|n|p|$)|rph|grid|ows|mnc|ntw|ine[ch]|zoo|^ord|itera/i,b=Array.isArray;function k(n,t){for(var i in t)n[i]=t[i];return n}function x(n){n&&n.parentNode&&n.parentNode.removeChild(n)}function S(n,i,e){var o,r,f,u={};for(f in i)"key"==f?o=i[f]:"ref"==f?r=i[f]:u[f]=i[f];if(arguments.length>2&&(u.children=arguments.length>3?t.call(arguments,2):e),"function"==typeof n&&n.defaultProps!=d)for(f in n.defaultProps)u[f]===w&&(u[f]=n.defaultProps[f]);return M(n,u,o,r,d)}function M(n,t,o,r,f){var u={type:n,props:t,key:o,ref:r,__k:d,__:d,__b:0,__e:d,__c:d,constructor:w,__v:f==d?++e:f,__i:-1,__u:0};return f==d&&i.vnode!=d&&i.vnode(u),u}function T(n){return n.children}function $(n,t){this.props=n,this.context=t}function C(n,t){if(t==d)return n.__?C(n.__,n.__i+1):d;for(var i;t<n.__k.length;t++)if((i=n.__k[t])!=d&&i.__e!=d)return i.__e;return"function"==typeof n.type?C(n):d}function I(n){if(n.__P&&n.__d){var t=n.__v,e=t.__e,o=[],r=[],f=k({},t);f.__v=t.__v+1,i.vnode&&i.vnode(f),q(n.__P,f,t,n.__n,n.__P.namespaceURI,32&t.__u?[e]:d,o,e==d?C(t):e,!!(32&t.__u),r),f.__v=t.__v,f.__.__k[f.__i]=f,D(o,f,r),t.__e=t.__=null,f.__e!=e&&P(f)}}function P(n){if((n=n.__)!=d&&n.__c!=d)return n.__e=n.__c.base=d,n.__k.some(function(t){if(t!=d&&t.__e!=d)return n.__e=n.__c.base=t.__e}),P(n)}function j(n){(!n.__d&&(n.__d=!0)&&r.push(n)&&!A.__r++||f!=i.debounceRendering)&&((f=i.debounceRendering)||u)(A)}function A(){for(var n,t=1;r.length;)r.length>t&&r.sort(c),n=r.shift(),t=r.length,I(n);A.__r=0}function H(n,t,i,e,o,r,f,u,c,s,a){var h,p,l,y,v,m,b,k=e&&e.__k||_,x=t.length;for(c=L(i,t,k,c,x),h=0;h<x;h++)(l=i.__k[h])!=d&&(p=-1!=l.__i&&k[l.__i]||g,l.__i=h,m=q(n,l,p,o,r,f,u,c,s,a),y=l.__e,l.ref&&p.ref!=l.ref&&(p.ref&&J(p.ref,d,l),a.push(l.ref,l.__c||y,l)),v==d&&y!=d&&(v=y),(b=!!(4&l.__u))||p.__k===l.__k?c=F(l,c,n,b):"function"==typeof l.type&&m!==w?c=m:y&&(c=y.nextSibling),l.__u&=-7);return i.__e=v,c}function L(n,t,i,e,o){var r,f,u,c,s,a=i.length,h=a,p=0;for(n.__k=new Array(o),r=0;r<o;r++)(f=t[r])!=d&&"boolean"!=typeof f&&"function"!=typeof f?("string"==typeof f||"number"==typeof f||"bigint"==typeof f||f.constructor==String?f=n.__k[r]=M(d,f,d,d,d):b(f)?f=n.__k[r]=M(T,{children:f},d,d,d):f.constructor===w&&f.__b>0?f=n.__k[r]=M(f.type,f.props,f.key,f.ref?f.ref:d,f.__v):n.__k[r]=f,c=r+p,f.__=n,f.__b=n.__b+1,s=f.__i=O(f,i,c,h),u=d,-1!=s&&(h--,(u=i[s])&&(u.__u|=2)),u==d||u.__v==d?(-1==s&&(o>a?p--:o<a&&p++),"function"!=typeof f.type&&(f.__u|=4)):s!=c&&(s==c-1?p--:s==c+1?p++:(s>c?p--:p++,f.__u|=4))):n.__k[r]=d;if(h)for(r=0;r<a;r++)(u=i[r])!=d&&0==(2&u.__u)&&(u.__e==e&&(e=C(u)),K(u,u));return e}function F(n,t,i,e){var o,r;if("function"==typeof n.type){for(o=n.__k,r=0;o&&r<o.length;r++)o[r]&&(o[r].__=n,t=F(o[r],t,i,e));return t}n.__e!=t&&(e&&(t&&n.type&&!t.parentNode&&(t=C(n)),i.insertBefore(n.__e,t||d)),t=n.__e);do{t=t&&t.nextSibling}while(t!=d&&8==t.nodeType);return t}function O(n,t,i,e){var o,r,f,u=n.key,c=n.type,s=t[i],a=s!=d&&0==(2&s.__u);if(s===d&&null==u||a&&u==s.key&&c==s.type)return i;if(e>(a?1:0))for(o=i-1,r=i+1;o>=0||r<t.length;)if((s=t[f=o>=0?o--:r++])!=d&&0==(2&s.__u)&&u==s.key&&c==s.type)return f;return-1}function z(n,t,i){"-"==t[0]?n.setProperty(t,i==d?"":i):n[t]=i==d?"":"number"!=typeof i||m.test(t)?i:i+"px"}function N(n,t,i,e,o){var r,f;n:if("style"==t)if("string"==typeof i)n.style.cssText=i;else{if("string"==typeof e&&(n.style.cssText=e=""),e)for(t in e)i&&t in i||z(n.style,t,"");if(i)for(t in i)e&&i[t]==e[t]||z(n.style,t,i[t])}else if("o"==t[0]&&"n"==t[1])r=t!=(t=t.replace(s,"$1")),f=t.toLowerCase(),t=f in n||"onFocusOut"==t||"onFocusIn"==t?f.slice(2):t.slice(2),n.l||(n.l={}),n.l[t+r]=i,i?e?i.t=e.t:(i.t=a,n.addEventListener(t,r?p:h,r)):n.removeEventListener(t,r?p:h,r);else{if(o==y)t=t.replace(/xlink(H|:h)/,"h").replace(/sName$/,"s");else if("width"!=t&&"height"!=t&&"href"!=t&&"list"!=t&&"form"!=t&&"tabIndex"!=t&&"download"!=t&&"rowSpan"!=t&&"colSpan"!=t&&"role"!=t&&"popover"!=t&&t in n)try{n[t]=i==d?"":i;break n}catch(n){}"function"==typeof i||(i==d||!1===i&&"-"!=t[4]?n.removeAttribute(t):n.setAttribute(t,"popover"==t&&1==i?"":i))}}function V(n){return function(t){if(this.l){var e=this.l[t.type+n];if(t.i==d)t.i=a++;else if(t.i<e.t)return;return e(i.event?i.event(t):t)}}}function q(n,t,e,o,r,f,u,c,s,a){var h,p,l,y,v,g,m,S,M,C,I,P,j,A,L,F=t.type;if(t.constructor!==w)return d;128&e.__u&&(s=!!(32&e.__u),f=[c=t.__e=e.__e]),(h=i.__b)&&h(t);n:if("function"==typeof F)try{if(S=t.props,M="prototype"in F&&F.prototype.render,C=(h=F.contextType)&&o[h.__c],I=h?C?C.props.value:h.__:o,e.__c?m=(p=t.__c=e.__c).__=p.__E:(M?t.__c=p=new F(S,I):(t.__c=p=new $(S,I),p.constructor=F,p.render=Q),C&&C.sub(p),p.state||(p.state={}),p.__n=o,l=p.__d=!0,p.__h=[],p._sb=[]),M&&p.__s==d&&(p.__s=p.state),M&&F.getDerivedStateFromProps!=d&&(p.__s==p.state&&(p.__s=k({},p.__s)),k(p.__s,F.getDerivedStateFromProps(S,p.__s))),y=p.props,v=p.state,p.__v=t,l)M&&F.getDerivedStateFromProps==d&&p.componentWillMount!=d&&p.componentWillMount(),M&&p.componentDidMount!=d&&p.__h.push(p.componentDidMount);else{if(M&&F.getDerivedStateFromProps==d&&S!==y&&p.componentWillReceiveProps!=d&&p.componentWillReceiveProps(S,I),t.__v==e.__v||!p.__e&&p.shouldComponentUpdate!=d&&!1===p.shouldComponentUpdate(S,p.__s,I)){t.__v!=e.__v&&(p.props=S,p.state=p.__s,p.__d=!1),t.__e=e.__e,t.__k=e.__k,t.__k.some(function(n){n&&(n.__=t)}),_.push.apply(p.__h,p._sb),p._sb=[],p.__h.length&&u.push(p);break n}p.componentWillUpdate!=d&&p.componentWillUpdate(S,p.__s,I),M&&p.componentDidUpdate!=d&&p.__h.push(function(){p.componentDidUpdate(y,v,g)})}if(p.context=I,p.props=S,p.__P=n,p.__e=!1,P=i.__r,j=0,M)p.state=p.__s,p.__d=!1,P&&P(t),h=p.render(p.props,p.state,p.context),_.push.apply(p.__h,p._sb),p._sb=[];else do{p.__d=!1,P&&P(t),h=p.render(p.props,p.state,p.context),p.state=p.__s}while(p.__d&&++j<25);p.state=p.__s,p.getChildContext!=d&&(o=k(k({},o),p.getChildContext())),M&&!l&&p.getSnapshotBeforeUpdate!=d&&(g=p.getSnapshotBeforeUpdate(y,v)),A=h!=d&&h.type===T&&h.key==d?E(h.props.children):h,c=H(n,b(A)?A:[A],t,e,o,r,f,u,c,s,a),p.base=t.__e,t.__u&=-161,p.__h.length&&u.push(p),m&&(p.__E=p.__=d)}catch(n){if(t.__v=d,s||f!=d)if(n.then){for(t.__u|=s?160:128;c&&8==c.nodeType&&c.nextSibling;)c=c.nextSibling;f[f.indexOf(c)]=d,t.__e=c}else{for(L=f.length;L--;)x(f[L]);B(t)}else t.__e=e.__e,t.__k=e.__k,n.then||B(t);i.__e(n,t,e)}else f==d&&t.__v==e.__v?(t.__k=e.__k,t.__e=e.__e):c=t.__e=G(e.__e,t,e,o,r,f,u,s,a);return(h=i.diffed)&&h(t),128&t.__u?void 0:c}function B(n){n&&(n.__c&&(n.__c.__e=!0),n.__k&&n.__k.some(B))}function D(n,t,e){for(var o=0;o<e.length;o++)J(e[o],e[++o],e[++o]);i.__c&&i.__c(t,n),n.some(function(t){try{n=t.__h,t.__h=[],n.some(function(n){n.call(t)})}catch(n){i.__e(n,t.__v)}})}function E(n){return"object"!=typeof n||n==d||n.__b>0?n:b(n)?n.map(E):k({},n)}function G(n,e,o,r,f,u,c,s,a){var h,p,l,_,m,k,S,M=o.props||g,T=e.props,$=e.type;if("svg"==$?f=y:"math"==$?f="http://www.w3.org/1998/Math/MathML":f||(f=v),u!=d)for(h=0;h<u.length;h++)if((m=u[h])&&"setAttribute"in m==!!$&&($?m.localName==$:3==m.nodeType)){n=m,u[h]=d;break}if(n==d){if($==d)return document.createTextNode(T);n=document.createElementNS(f,$,T.is&&T),s&&(i.__m&&i.__m(e,u),s=!1),u=d}if($==d)M===T||s&&n.data==T||(n.data=T);else{if(u=u&&t.call(n.childNodes),!s&&u!=d)for(M={},h=0;h<n.attributes.length;h++)M[(m=n.attributes[h]).name]=m.value;for(h in M)m=M[h],"dangerouslySetInnerHTML"==h?l=m:"children"==h||h in T||"value"==h&&"defaultValue"in T||"checked"==h&&"defaultChecked"in T||N(n,h,d,m,f);for(h in T)m=T[h],"children"==h?_=m:"dangerouslySetInnerHTML"==h?p=m:"value"==h?k=m:"checked"==h?S=m:s&&"function"!=typeof m||M[h]===m||N(n,h,m,M[h],f);if(p)s||l&&(p.__html==l.__html||p.__html==n.innerHTML)||(n.innerHTML=p.__html),e.__k=[];else if(l&&(n.innerHTML=""),H("template"==e.type?n.content:n,b(_)?_:[_],e,o,r,"foreignObject"==$?v:f,u,c,u?u[0]:o.__k&&C(o,0),s,a),u!=d)for(h=u.length;h--;)x(u[h]);s||(h="value","progress"==$&&k==d?n.removeAttribute("value"):k!=w&&(k!==n[h]||"progress"==$&&!k||"option"==$&&k!=M[h])&&N(n,h,k,M[h],f),h="checked",S!=w&&S!=n[h]&&N(n,h,S,M[h],f))}return n}function J(n,t,e){try{if("function"==typeof n){var o="function"==typeof n.__u;o&&n.__u(),o&&t==d||(n.__u=n(t))}else n.current=t}catch(n){i.__e(n,e)}}function K(n,t,e){var o,r;if(i.unmount&&i.unmount(n),(o=n.ref)&&(o.current&&o.current!=n.__e||J(o,d,t)),(o=n.__c)!=d){if(o.componentWillUnmount)try{o.componentWillUnmount()}catch(n){i.__e(n,t)}o.base=o.__P=d}if(o=n.__k)for(r=0;r<o.length;r++)o[r]&&K(o[r],t,e||"function"!=typeof n.type);e||x(n.__e),n.__c=n.__=n.__e=w}function Q(n,t,i){return this.constructor(n,i)}function R(n,e,o){var r,f,u,c;e==document&&(e=document.documentElement),i.__&&i.__(n,e),f=(r="function"==typeof o)?d:o&&o.__k||e.__k,u=[],c=[],q(e,n=(!r&&o||e).__k=S(T,d,[n]),f||g,g,e.namespaceURI,!r&&o?[o]:f?d:e.firstChild?t.call(e.childNodes):d,u,!r&&o?o:f?f.__e:e.firstChild,r,c),D(u,n,c)}t=_.slice,i={__e:function(n,t,i,e){for(var o,r,f;t=t.__;)if((o=t.__c)&&!o.__)try{if((r=o.constructor)&&r.getDerivedStateFromError!=d&&(o.setState(r.getDerivedStateFromError(n)),f=o.__d),o.componentDidCatch!=d&&(o.componentDidCatch(n,e||{}),f=o.__d),f)return o.__E=o}catch(t){n=t}throw n}},e=0,o=function(n){return n!=d&&n.constructor===w},$.prototype.setState=function(n,t){var i;i=this.__s!=d&&this.__s!=this.state?this.__s:this.__s=k({},this.state),"function"==typeof n&&(n=n(k({},i),this.props)),n&&k(i,n),n!=d&&this.__v&&(t&&this._sb.push(t),j(this))},$.prototype.forceUpdate=function(n){this.__v&&(this.__e=!0,n&&this.__h.push(n),j(this))},$.prototype.render=T,r=[],u="function"==typeof Promise?Promise.prototype.then.bind(Promise.resolve()):setTimeout,c=function(n,t){return n.__v.__b-t.__v.__b},A.__r=0,s=/(PointerCapture)$|Capture$/i,a=0,h=V(!1),p=V(!0),l=0,n.Component=$,n.Fragment=T,n.cloneElement=function(n,i,e){var o,r,f,u,c=k({},n.props);for(f in n.type&&n.type.defaultProps&&(u=n.type.defaultProps),i)"key"==f?o=i[f]:"ref"==f?r=i[f]:c[f]=i[f]===w&&u!=w?u[f]:i[f];return arguments.length>2&&(c.children=arguments.length>3?t.call(arguments,2):e),M(n.type,c,o||n.key,r||n.ref,d)},n.createContext=function(n){function t(n){var i,e;return this.getChildContext||(i=new Set,(e={})[t.__c]=this,this.getChildContext=function(){return e},this.componentWillUnmount=function(){i=d},this.shouldComponentUpdate=function(n){this.props.value!=n.value&&i.forEach(function(n){n.__e=!0,j(n)})},this.sub=function(n){i.add(n);var t=n.componentWillUnmount;n.componentWillUnmount=function(){i&&i.delete(n),t&&t.call(n)}}),n.children}return t.__c="__cC"+l++,t.__=n,t.Provider=t.__l=(t.Consumer=function(n,t){return n.children(t)}).contextType=t,t},n.createElement=S,n.createRef=function(){return{current:d}},n.h=S,n.hydrate=function n(t,i){R(t,i,n)},n.isValidElement=o,n.options=i,n.render=R,n.toChildArray=function n(t,i){return i=i||[],t==d||"boolean"==typeof t||(b(t)?t.some(function(t){n(t,i)}):i.push(t)),i}});
//# sourceMappingURL=preact.umd.js.map

}).call(scope);
(function () {
!function(n,t){"object"==typeof exports&&"undefined"!=typeof module?t(exports,require("preact")):"function"==typeof define&&define.amd?define(["exports","preact"],t):t((n||self).preactHooks={},n.preact)}(this,function(n,t){var u,r,i,o,f=0,c=[],e=t.options,a=e.__b,v=e.__r,l=e.diffed,d=e.__c,s=e.unmount,p=e.__;function y(n,t){e.__h&&e.__h(r,n,f||t),f=0;var u=r.__H||(r.__H={__:[],__h:[]});return n>=u.__.length&&u.__.push({}),u.__[n]}function h(n){return f=1,m(j,n)}function m(n,t,i){var o=y(u++,2);if(o.t=n,!o.__c&&(o.__=[i?i(t):j(void 0,t),function(n){var t=o.__N?o.__N[0]:o.__[0],u=o.t(t,n);t!==u&&(o.__N=[u,o.__[1]],o.__c.setState({}))}],o.__c=r,!r.__f)){var f=function(n,t,u){if(!o.__c.__H)return!0;var r=o.__c.__H.__.filter(function(n){return n.__c});if(r.every(function(n){return!n.__N}))return!c||c.call(this,n,t,u);var i=o.__c.props!==n;return r.some(function(n){if(n.__N){var t=n.__[0];n.__=n.__N,n.__N=void 0,t!==n.__[0]&&(i=!0)}}),c&&c.call(this,n,t,u)||i};r.__f=!0;var c=r.shouldComponentUpdate,e=r.componentWillUpdate;r.componentWillUpdate=function(n,t,u){if(this.__e){var r=c;c=void 0,f(n,t,u),c=r}e&&e.call(this,n,t,u)},r.shouldComponentUpdate=f}return o.__N||o.__}function T(n,t){var i=y(u++,4);!e.__s&&g(i.__H,t)&&(i.__=n,i.u=t,r.__h.push(i))}function _(n,t){var r=y(u++,7);return g(r.__H,t)&&(r.__=n(),r.__H=t,r.__h=n),r.__}function b(){for(var n;n=c.shift();){var t=n.__H;if(n.__P&&t)try{t.__h.some(A),t.__h.some(F),t.__h=[]}catch(u){t.__h=[],e.__e(u,n.__v)}}}e.__b=function(n){r=null,a&&a(n)},e.__=function(n,t){n&&t.__k&&t.__k.__m&&(n.__m=t.__k.__m),p&&p(n,t)},e.__r=function(n){v&&v(n),u=0;var t=(r=n.__c).__H;t&&(i===r?(t.__h=[],r.__h=[],t.__.some(function(n){n.__N&&(n.__=n.__N),n.u=n.__N=void 0})):(t.__h.some(A),t.__h.some(F),t.__h=[],u=0)),i=r},e.diffed=function(n){l&&l(n);var t=n.__c;t&&t.__H&&(t.__H.__h.length&&(1!==c.push(t)&&o===e.requestAnimationFrame||((o=e.requestAnimationFrame)||x)(b)),t.__H.__.some(function(n){n.u&&(n.__H=n.u),n.u=void 0})),i=r=null},e.__c=function(n,t){t.some(function(n){try{n.__h.some(A),n.__h=n.__h.filter(function(n){return!n.__||F(n)})}catch(u){t.some(function(n){n.__h&&(n.__h=[])}),t=[],e.__e(u,n.__v)}}),d&&d(n,t)},e.unmount=function(n){s&&s(n);var t,u=n.__c;u&&u.__H&&(u.__H.__.some(function(n){try{A(n)}catch(n){t=n}}),u.__H=void 0,t&&e.__e(t,u.__v))};var q="function"==typeof requestAnimationFrame;function x(n){var t,u=function(){clearTimeout(r),q&&cancelAnimationFrame(t),setTimeout(n)},r=setTimeout(u,35);q&&(t=requestAnimationFrame(u))}function A(n){var t=r,u=n.__c;"function"==typeof u&&(n.__c=void 0,u()),r=t}function F(n){var t=r;n.__c=n.__(),r=t}function g(n,t){return!n||n.length!==t.length||t.some(function(t,u){return t!==n[u]})}function j(n,t){return"function"==typeof t?t(n):t}n.useCallback=function(n,t){return f=8,_(function(){return n},t)},n.useContext=function(n){var t=r.context[n.__c],i=y(u++,9);return i.c=n,t?(null==i.__&&(i.__=!0,t.sub(r)),t.props.value):n.__},n.useDebugValue=function(n,t){e.useDebugValue&&e.useDebugValue(t?t(n):n)},n.useEffect=function(n,t){var i=y(u++,3);!e.__s&&g(i.__H,t)&&(i.__=n,i.u=t,r.__H.__h.push(i))},n.useErrorBoundary=function(n){var t=y(u++,10),i=h();return t.__=n,r.componentDidCatch||(r.componentDidCatch=function(n,u){t.__&&t.__(n,u),i[1](n)}),[i[0],function(){i[1](void 0)}]},n.useId=function(){var n=y(u++,11);if(!n.__){for(var t=r.__v;null!==t&&!t.__m&&null!==t.__;)t=t.__;var i=t.__m||(t.__m=[0,0]);n.__="P"+i[0]+"-"+i[1]++}return n.__},n.useImperativeHandle=function(n,t,u){f=6,T(function(){if("function"==typeof n){var u=n(t());return function(){n(null),u&&"function"==typeof u&&u()}}if(n)return n.current=t(),function(){return n.current=null}},null==u?u:u.concat(n))},n.useLayoutEffect=T,n.useMemo=_,n.useReducer=m,n.useRef=function(n){return f=5,_(function(){return{current:n}},[])},n.useState=h});
//# sourceMappingURL=hooks.umd.js.map

}).call(scope);
return scope;
}
// 最小 DOM(SPEC 7.1):只实现 Preact 真正会碰的那一层,每次变更记成一条 op。
//
// ★ 为什么是「假 DOM」而不是「改 Preact」:Preact 的渲染器写死了 DOM,
//   但它碰到的 DOM 面其实很窄。给它一个假的,Hooks / Context / Fragment / key
//   全部原样可用,而且 Preact 本体一行不改 —— 升级时不用重做适配。
// ☠ 代价是**这一层跟着 Preact 的内部实现走**:哪天它开始用新的 DOM 接口,
//   症状是「某些节点不更新」而不是报错。ui_test.go 那组用例就是为那一天存在的。
//
// ★ 一份 document 管所有 surface:节点 id 全局唯一,ops 先**挂在节点自己身上**,
//   等它被接进某棵树时才知道属于哪个 surface,这时候整条挂着的 ops 一起放出去。
//   Preact 是自底向上建树的(先给父节点塞子节点,最后才把父节点接上根),
//   所以「接上根的那一刻才路由」是唯一不需要猜的时机 ——
//   靠 `options` 之类的内部钩子去猜「现在在渲染哪个 surface」才是会碎的写法。

/** SPEC 7.5 的样式子集。Preact 对名为 `style` 的属性会**逐键**写进 dom.style,
 *  所以这里给每个已知键定义 setter 把它收回来 —— 不用 Proxy,也不用每帧扫一遍对象
 *  (1000 项列表那种场合扫不起)。 */
const STYLE_KEYS = [
  'direction', 'justify', 'align', 'gap', 'grow', 'shrink', 'basis', 'wrap',
  'width', 'height', 'minWidth', 'maxWidth', 'minHeight', 'maxHeight',
  'padding', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
  'margin', 'marginTop', 'marginRight', 'marginBottom', 'marginLeft',
  'position', 'inset', 'top', 'left', 'right', 'bottom', 'zIndex', 'aspectRatio',
  'background', 'opacity', 'radius', 'borderWidth', 'borderColor', 'shadow',
  'backdropBlur', 'overflow',
  'color', 'fontSize', 'fontWeight', 'fontFamily', 'lineHeight', 'textAlign',
  'maxLines', 'letterSpacing',
  'fit', 'tint',
  'translateX', 'translateY', 'scale', 'rotate',
  'transition', 'animation',
]

/* ☠ Preact 写 style 时会给**数字**自动补 px(它以为自己在跟 CSS 打交道),
   于是插件写的 `fontSize: 22` 到壳那边变成字符串 "22px" ——
   壳按数字读,读不到,表现是「样式全都没生效」而不报错。
   这里还原成数字:我们的协议里长度就是数字(设备无关像素,SPEC 7.5),不是 CSS。 */
function unpx(v) {
  if (typeof v !== 'string') return v
  const m = /^(-?\d+(?:\.\d+)?)px$/.exec(v)
  return m ? Number(m[1]) : v
}

function makeDom(router) {
  let nextId = 1

  /** 一条 op 的去处:节点已在某棵树上就直接进那个 surface 的缓冲,否则先挂在节点上。 */
  function emit(n, op) {
    if (n.__lproot) router.push(n.__lproot, op)
    else n.__lppending.push(op)
  }

  /** 把 c 及其整棵子树接到 root 上:先放它自己攒下的 ops,再放 insert,再递归子节点。 */
  function attach(c, root, parentId, beforeId) {
    c.__lproot = root
    const pend = c.__lppending
    c.__lppending = []
    for (const op of pend) router.push(root, op)
    router.push(root, { op: 'insert', parent: parentId, id: c.__lpid, before: beforeId })
    for (const g of c.childNodes) {
      // 子节点是在 c 还没接上根时塞进来的,它们的 insert 这会儿才补发
      attach(g, root, c.__lpid, null)
    }
  }

  function baseNode(nodeType) {
    return {
      nodeType,
      parentNode: null,
      childNodes: [],
      __lproot: null,
      __lppending: [],
      get firstChild() {
        return this.childNodes[0] || null
      },
      get nextSibling() {
        const p = this.parentNode
        if (!p) return null
        const i = p.childNodes.indexOf(this)
        return i < 0 ? null : p.childNodes[i + 1] || null
      },
      appendChild(c) {
        return this.insertBefore(c, null)
      },
      insertBefore(c, ref) {
        const moving = c.__lproot !== null && c.parentNode !== null
        if (c.parentNode) c.parentNode.detach(c)
        const at = ref ? this.childNodes.indexOf(ref) : -1
        if (at < 0) this.childNodes.push(c)
        else this.childNodes.splice(at, 0, c)
        c.parentNode = this
        const beforeId = ref ? ref.__lpid : null
        if (moving && c.__lproot === this.__lproot) {
          // 同一棵树里挪位置:壳收到「已存在的 id 又被 insert 一次」= 移动,不是新建
          router.push(c.__lproot, { op: 'insert', parent: this.__lpid, id: c.__lpid, before: beforeId })
        } else if (this.__lproot) {
          attach(c, this.__lproot, this.__lpid, beforeId)
        }
        return c
      },
      /** 只从树上摘下来,不发 remove —— 紧接着会 insert 到别处。 */
      detach(c) {
        const at = this.childNodes.indexOf(c)
        if (at >= 0) this.childNodes.splice(at, 1)
        c.parentNode = null
      },
      removeChild(c) {
        this.detach(c)
        if (c.__lproot) {
          router.push(c.__lproot, { op: 'remove', id: c.__lpid })
          router.dropSubtree(c.__lproot, c)
        }
        return c
      },
      remove() {
        if (this.parentNode) this.parentNode.removeChild(this)
      },
    }
  }

  function makeStyle(n) {
    const store = {}
    const flush = () => setProp(n, 'style', store)
    const s = {
      setProperty(k, v) {
        store[k] = v
        flush()
      },
      removeProperty(k) {
        delete store[k]
        flush()
      },
      get cssText() {
        return ''
      },
      set cssText(_v) {
        for (const k of Object.keys(store)) delete store[k]
        flush()
      },
    }
    for (const k of STYLE_KEYS) {
      Object.defineProperty(s, k, {
        get: () => store[k],
        set: (v) => {
          if (v === undefined || v === null || v === '') delete store[k]
          else store[k] = unpx(v)
          flush()
        },
      })
    }
    return s
  }

  function setProp(n, name, value) {
    if (value === undefined || value === null || value === false) {
      if (!(name in n.__lpprops)) return
      delete n.__lpprops[name]
      router.dropFn(n.__lpid, name)
      emit(n, { op: 'props', id: n.__lpid, unset: [name] })
      return
    }
    n.__lpprops[name] = value
    emit(n, { op: 'props', id: n.__lpid, set: { [name]: router.value(n.__lpid, name, value) } })
  }

  function createElement(type) {
    const n = baseNode(1)
    n.__lpid = nextId++
    n.__lptype = type
    n.__lpprops = {}
    n.__lppending.push({ op: 'create', id: n.__lpid, type })
    n.style = makeStyle(n)
    n.setAttribute = (name, value) => setProp(n, name, value)
    n.removeAttribute = (name) => setProp(n, name, undefined)
    /* ☠ Preact 传进来的 **不是**插件写的那个函数,而是它自己的 eventProxy ——
       真正的处理函数藏在节点上一个**被压缩过名字**的内部字段里,拿不到。
       所以这里存一层包装:触发时按原样调那个代理(`this` 必须是节点、`e.type`
       必须是 Preact 当初给的名字),让它自己去查真处理函数。
       插件那边要收到的是原始参数而不是这个假事件,那一步由 renderer.js 的
       `options.event` 还原 —— 那是 Preact 的公开钩子,不是内部字段。
       ★ 附带的好处:处理函数**换实现时 Preact 不会再调一次 addEventListener**,
       所以回调号跨重渲染是稳定的,少一大批 props op。 */
    n.addEventListener = (name, proxy) => {
      setProp(n, 'on' + name, function () {
        const args = Array.prototype.slice.call(arguments)
        return proxy.call(n, { type: name, __lpargs: args })
      })
    }
    n.removeEventListener = (name) => setProp(n, 'on' + name, undefined)
    return n
  }

  function createTextNode(data) {
    const n = baseNode(3)
    n.__lpid = nextId++
    n.__lpprops = {}
    let cur = String(data)
    n.__lppending.push({ op: 'create', id: n.__lpid, type: '#text' })
    n.__lppending.push({ op: 'text', id: n.__lpid, value: cur })
    Object.defineProperty(n, 'data', {
      get: () => cur,
      set: (v) => {
        cur = String(v)
        emit(n, { op: 'text', id: n.__lpid, value: cur })
      },
    })
    n.style = {}
    n.setAttribute = () => {}
    n.removeAttribute = () => {}
    n.addEventListener = () => {}
    n.removeEventListener = () => {}
    return n
  }

  /** surface 的根:id 恒为 0,不发 create —— 壳那边的容器已经在了。 */
  function createRoot(surfaceId) {
    const n = baseNode(1)
    n.__lpid = 0
    n.__lptype = '#root'
    n.__lpprops = {}
    n.__lproot = surfaceId
    n.style = {}
    n.setAttribute = () => {}
    n.removeAttribute = () => {}
    n.addEventListener = () => {}
    n.removeEventListener = () => {}
    return n
  }

  return {
    createElement,
    createElementNS: (_ns, type) => createElement(type),
    createTextNode,
    createRoot,
  }
}

// surface 渲染器(SPEC 7.2 7.3):Preact 的 DOM 变更 → ops → 一帧一条发给壳。
//
// ★ 一份 Preact、一份 document,多个 surface 共存;节点 id 全局唯一,
//   ops 靠 dom.js 的「接上根时才路由」分流。回调号也全局唯一 ——
//   号跨 surface 复用的话,壳上一次迟到的点击会打到另一块 UI 上。

function makeRenderer(makeScope, host) {
  const surfaces = new Map()
  const fns = new Map() // 回调号 → {fn, node}
  const fnByPath = new Map() // "节点id.属性名" → 回调号
  let fnSeq = 1

  const router = {
    push(surfaceId, op) {
      const s = surfaces.get(surfaceId)
      if (!s) return
      s.ops.push(op)
      schedule(s)
    },
    /** 函数属性 → {$fn:n};其余递归。同一个 id.属性 重复设值时旧号当场作废。 */
    value(id, name, v) {
      return serialize(id + '.' + name, v)
    },
    dropFn(id, name) {
      const path = id + '.' + name
      const n = fnByPath.get(path)
      if (n === undefined) return
      fns.delete(n)
      fnByPath.delete(path)
    },
    /** 节点连同子树被删:它们的回调号一起作废,否则壳上迟到的点击会调进已卸载的闭包。 */
    dropSubtree(_surfaceId, node) {
      const stack = [node]
      while (stack.length) {
        const n = stack.pop()
        for (const k of Object.keys(n.__lpprops || {})) router.dropFn(n.__lpid, k)
        for (const c of n.childNodes) stack.push(c)
      }
    },
  }

  function serialize(path, v) {
    if (typeof v === 'function') {
      const old = fnByPath.get(path)
      if (old !== undefined) fns.delete(old)
      const n = fnSeq++
      fns.set(n, v)
      fnByPath.set(path, n)
      return { $fn: n }
    }
    if (v === null || typeof v !== 'object') return v
    if (Array.isArray(v)) return v.map((x, i) => serialize(path + '.' + i, x))
    const out = {}
    for (const k of Object.keys(v)) out[k] = serialize(path + '.' + k, v[k])
    return out
  }

  // 顺序是定死的:先有 router 才能造 document,先有 document 才能造绑着它的那份 Preact
  const document = makeDom(router)
  const scope = makeScope(document)
  const preact = scope.preact
  const hooks = scope.preactHooks

  /* 把 dom.js 造的假事件还原成插件写的参数。`options.event` 是 Preact 的公开钩子,
     在处理函数拿到事件**之前**跑。SDK 里所有回调都是 0 或 1 个参数
     (onPress() / onChangeText(text) / renderItem(i)),所以取第一个就够。 */
  preact.options.event = (e) => (e && e.__lpargs ? e.__lpargs[0] : e)

  /**
   * 错误边界(D136):一块崩了只让那一块显示「出错」,不带倒整页,更不带倒别的插件。
   * 渲染期的错误走 useErrorBoundary;事件回调里抛的由 event() 兜。
   */
  function Boundary(props) {
    const [err, setErr] = hooks.useState(null)
    hooks.useErrorBoundary((e) => setErr(e))
    if (err) {
      host.surfaceState(props.__surface, 'error', {
        message: String((err && err.message) || err),
        stack: (err && err.stack) || '',
      })
      return null
    }
    return props.__render()
  }

  function flush(s) {
    s.scheduled = false
    if (!s.ops.length) return
    const ops = s.ops
    s.ops = []
    host.frame(s.id, ++s.frame, ops)
  }

  /** 一次交互里的多次 setState 落进同一帧(D318)。 */
  function schedule(s) {
    if (s.scheduled) return
    s.scheduled = true
    host.nextFrame(() => flush(s))
  }

  /* 视口与安全区(SPEC 7.7,D217 D425 D426)。
     ★ 一个 surface 一份:同一个插件的页和侧栏区块视口本来就不一样。
     ☠ 默认值不能是 0:插件在收到第一条 viewport 之前就要渲染一次,
       宽度读成 0 的话按断点分支的布局会全走 compact,而那一帧是用户真看得见的。 */
  const viewports = new Map()
  const vpListeners = new Map()

  function viewportOf(id) {
    let v = viewports.get(id)
    if (!v) {
      v = { width: 1280, height: 720, breakpoint: 'expanded', formFactor: 'desktop', insets: { top: 0, right: 0, bottom: 0, left: 0 } }
      viewports.set(id, v)
    }
    return v
  }

  /**
   * useViewport():订阅当前 surface 的视口,变了就重渲染。
   *
   * ☠ 订阅**在渲染期就挂上**,不放进 useEffect:没有 requestAnimationFrame 时
   * Preact 的 effect 要等 100ms 才跑,而壳常常在挂载后立刻报一次尺寸 ——
   * 那一条正好落在这个窗口里,没人接。表现是「安全区永远是 0」,不报错。
   */
  function useViewport() {
    const id = hooks.useContext(SurfaceCtx)
    const [, bump] = hooks.useState(0)
    const ref = hooks.useRef(null)
    if (!ref.current) {
      ref.current = () => bump((n) => n + 1)
      let set = vpListeners.get(id)
      if (!set) {
        set = new Set()
        vpListeners.set(id, set)
      }
      set.add(ref.current)
    }
    hooks.useEffect(() => () => {
      const set = vpListeners.get(id)
      if (set) set.delete(ref.current)
    }, [id])
    return viewportOf(id)
  }

  const SurfaceCtx = preact.createContext('')

  return {
    useViewport,
    /** 壳报来的视口变化。节流由壳那边做(每帧最多一条)。 */
    viewport(surfaceId, v) {
      const cur = viewportOf(surfaceId)
      if (
        cur.width === v.width && cur.height === v.height &&
        cur.breakpoint === v.breakpoint && cur.formFactor === v.formFactor &&
        JSON.stringify(cur.insets) === JSON.stringify(v.insets)
      ) return
      viewports.set(surfaceId, v)
      const set = vpListeners.get(surfaceId)
      if (set) for (const fn of set) fn()
    },
    mount(surfaceId, render) {
      const s = { id: surfaceId, ops: [], scheduled: false, frame: 0 }
      s.root = document.createRoot(surfaceId)
      surfaces.set(surfaceId, s)
      // 根要先告诉壳:后面所有 parent 为 0 的 insert 指的就是它
      s.ops.push({ op: 'root', id: 0 })
      // 用 Context 把 surface id 传下去:useViewport 要知道自己属于哪一块
      preact.render(
        preact.h(SurfaceCtx.Provider, { value: surfaceId },
          preact.h(Boundary, { __surface: surfaceId, __render: render })),
        s.root,
      )
      flush(s)
      host.surfaceState(surfaceId, 'ready')
      return surfaceId
    },
    unmount(surfaceId) {
      const s = surfaces.get(surfaceId)
      if (!s) return
      viewports.delete(surfaceId)
      vpListeners.delete(surfaceId)
      preact.render(null, s.root)
      router.dropSubtree(surfaceId, s.root)
      surfaces.delete(surfaceId)
      // 卸载后不再发帧:这一份 ops 没人要了
      s.ops = []
    },
    /** 壳回传的一次交互。回调里抛错不许冒到宿主 —— 那会把整个事件循环带走。 */
    event(surfaceId, fn, args) {
      if (!surfaces.has(surfaceId)) return
      const cb = fns.get(fn)
      // 号作废是常态(属性更新过、节点已卸载),不是错误 —— 悄悄丢
      if (!cb) return
      try {
        cb.apply(null, args || [])
      } catch (e) {
        host.surfaceState(surfaceId, 'error', {
          message: String((e && e.message) || e),
          stack: (e && e.stack) || '',
        })
      }
    },
    // h / Fragment / hooks 必须来自**这一份** Preact:hooks 挂的是它的 options,
    // 换一份就全断了(而且断得没有报错,只是 useState 永远拿不到更新)
    preact,
    hooks,
    // 这两个是**真组件**不是字符串:窗口内的项由 JS 渲染,壳只报可见范围(D134)
    VirtualList: makeVirtualList(preact, hooks, 'VirtualList'),
    VirtualGrid: makeVirtualList(preact, hooks, 'VirtualGrid'),
    surfaceIds: () => Array.from(surfaces.keys()),
    /** 给测试与基准用:当前挂着多少个回调号。泄漏了这个数会一路涨。 */
    fnCount: () => fns.size,
  }
}

/**
 * VirtualList / VirtualGrid(D134):插件给 itemCount + renderItem(i),
 * 原生端只向 JS 要**可见范围**的那些项。
 *
 * ★ 不另造一条通道:可见范围就是一次普通的回调(`onRange`),走已有的 {$fn} 机制。
 *   新开一条 `plugin.ui.range` 命令的话,回调号作废、surface 卸载这些规矩全要再写一遍。
 * ☠ 首屏那一窗**必须由 JS 先给一批**:等原生端报范围再渲染的话,
 *   第一帧是空的,壳那边量到的「首帧」就成了一个空列表。
 */
function makeVirtualList(preact, hooks, type) {
  return function VirtualList(props) {
    const count = props.itemCount | 0
    const initial = Math.min(count, props.initialWindow || 24)
    const [win, setWin] = hooks.useState({ from: 0, to: initial })
    const from = Math.max(0, Math.min(win.from, Math.max(0, count - 1)))
    const to = Math.min(count, Math.max(win.to, from))

    const kids = []
    for (let i = from; i < to; i++) {
      const child = props.renderItem(i)
      // key 必须是**真实下标**:窗口一滑,同一个位置换成了另一条数据,
      // 用相对下标当 key 会让 Preact 认成「同一项改了内容」,状态串到别的项上
      kids.push(preact.h(preact.Fragment, { key: 'v' + i }, child))
    }
    return preact.h(
      type,
      {
        style: props.style,
        itemCount: count,
        itemHeight: props.itemHeight,
        columns: props.columns,
        horizontal: props.horizontal,
        firstIndex: from,
        onRange: (r) => {
          if (!r) return
          const f = r.from | 0
          const t = r.to | 0
          if (f !== win.from || t !== win.to) setWin({ from: f, to: t })
        },
        onEndReached: props.onEndReached,
      },
      kids,
    )
  }
}

g.__lp_ui_init = function (host) { return makeRenderer(__lp_makeScope, host); };
g.__lp_preact_version = "10.28.4";
})(this);
