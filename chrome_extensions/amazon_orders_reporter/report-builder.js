/**
 * report-builder.js
 * Parses Amazon "Your Orders" live DOM pages, classifies products with confidence scoring:
 * - Any food/beverage item -> "Grocery"
 * - Sports/workout/athletic items -> "Sports & Fitness"
 * - Large sets of purchases in late Nov / early Dec -> "Christmas Presents"
 * - Low-confidence / unknown items -> "UNCATEGORIZED"
 * Supports user-customizable High-Level & Finer-Grain categories persisted in extension cache,
 * and builds a single self-contained HTML report with Sortable Order Placed / Delivery Date
 * as the first column.
 */

(function (global) {
  'use strict';

  const DEFAULT_HIGH_CATEGORIES = [
    'Automotive',
    'Baby & Toys',
    'Beauty',
    'Books & Media',
    'Christmas Presents',
    'Clothing',
    'Computing',
    'Electronics',
    'Grocery',
    'Health & Wellness',
    'Home & Kitchen',
    'Office & School',
    'Pet Supplies',
    'Sports & Fitness',
    'Tools & Home Improvement',
    'UNCATEGORIZED'
  ];

  function sortAlpha(list) {
    return Array.from(new Set((list || []).map((s) => String(s || '').trim()).filter(Boolean))).sort((a, b) =>
      a.localeCompare(b, undefined, { sensitivity: 'base' })
    );
  }

  function resolveUrl(href, baseUrl) {
    if (!href) return '';
    const trimmed = href.trim();
    if (!trimmed || trimmed.startsWith('javascript:') || trimmed === '#') return '';
    try {
      return new URL(trimmed, baseUrl || window.location.origin).href;
    } catch (_) {
      return trimmed;
    }
  }

  function cleanText(str) {
    if (!str) return '';
    return str.replace(/\s+/g, ' ').trim();
  }

  function normalizeTitleKey(title) {
    return cleanText(title || '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, ' ')
      .trim()
      .slice(0, 120);
  }

  function parseCurrencyAmount(str) {
    if (!str) return { amount: 0, currency: '$' };
    const cleaned = cleanText(str);
    const currencyMatch = cleaned.match(/([$£€¥]|USD|CAD|GBP|EUR|AUD|JPY|INR|MXN|BRL)/i);
    const currency = currencyMatch ? currencyMatch[1] : '$';
    const numMatch = cleaned.replace(/,/g, '').match(/-?\d+(?:\.\d+)?/);
    const amount = numMatch ? parseFloat(numMatch[0]) : 0;
    return { amount: isNaN(amount) ? 0 : amount, currency };
  }

  function toIsoDate(dateStr) {
    if (!dateStr) return '';
    const cleaned = cleanText(dateStr)
      .replace(/\(est\.\)/gi, '')
      .replace(/\(Order Date\)/gi, '')
      .replace(/^(?:Delivered|Arriving|Expected|Shipped)\s+/i, '')
      .replace(/^~/, '')
      .trim();
    const parsed = new Date(cleaned);
    if (!isNaN(parsed.getTime())) {
      const y = parsed.getFullYear();
      const m = String(parsed.getMonth() + 1).padStart(2, '0');
      const d = String(parsed.getDate()).padStart(2, '0');
      return `${y}-${m}-${d}`;
    }
    return '';
  }

  function formatShortDate(dateObj) {
    if (!dateObj || isNaN(dateObj.getTime())) return '';
    const months = [
      'January', 'February', 'March', 'April', 'May', 'June',
      'July', 'August', 'September', 'October', 'November', 'December'
    ];
    return `${months[dateObj.getMonth()]} ${dateObj.getDate()}, ${dateObj.getFullYear()}`;
  }

  function extractAsinFromUrl(url) {
    if (!url) return '';
    const m = url.match(/\/(?:dp|gp\/product|product)\/([A-Z0-9]{10})(?:[/?&#]|$)/i);
    return m ? m[1].toUpperCase() : '';
  }

  /**
   * Two-tier Product Categorization Taxonomy with Confidence Scoring:
   * - Any food/edible item -> High-Level Category: "Grocery"
   * - Sports/athletic/workout items -> High-Level Category: "Sports & Fitness"
   * - Anything that cannot be categorized with confidence -> "UNCATEGORIZED"
   */
  const CATEGORY_TAXONOMY = [
    // 1. GROCERY (All food, snack, beverage, pantry, candy, coffee/tea, culinary items)
    {
      high: 'Grocery',
      fine: 'Cereal, Oats & Muesli',
      keywords: [
        'muesli', 'oatmeal', 'rolled oats', 'steel cut oats', 'granola', 'cereal',
        "bob's red mill", 'porridge', 'cheerios', 'corn flakes', 'bran flakes',
        'chia seeds', 'flaxseed', 'hemp seeds', 'grits', 'cream of wheat', 'pancake mix', 'waffle mix'
      ]
    },
    {
      high: 'Grocery',
      fine: 'Coffee, Tea & Espresso',
      keywords: [
        'coffee beans', 'ground coffee', 'whole bean coffee', 'instant coffee', 'espresso',
        'k-cup', 'k-cups', 'nespresso', 'cold brew', 'matcha', 'green tea', 'black tea',
        'herbal tea', 'chamomile tea', 'peppermint tea', 'earl grey', 'tea bags', 'loose leaf tea',
        'chai', 'decaf coffee', 'coffee pods', 'coffee creamer'
      ]
    },
    {
      high: 'Grocery',
      fine: 'Snacks, Nuts, Bars & Crisps',
      keywords: [
        'almonds', 'cashews', 'pistachios', 'walnuts', 'pecans', 'macadamia', 'peanuts',
        'mixed nuts', 'trail mix', 'protein bar', ' protein bars', 'energy bar', 'snack bar',
        'granola bar', 'fig bar', 'beef jerky', 'turkey jerky', 'biltong', 'meat sticks',
        'popcorn', 'potato chips', 'tortilla chips', 'pita chips', 'pretzels', 'crackers',
        'cheese crisps', 'cookies', 'biscotti', 'shortbread', 'wafers', 'rice cakes',
        'seaweed snack', 'fruit snacks', 'apple sauce', 'dried mango', 'raisins', 'cranberries',
        'dates', 'prunes', 'sunflower seeds', 'pumpkin seeds', 'pork rinds'
      ]
    },
    {
      high: 'Grocery',
      fine: 'Chocolate, Candy & Sweets',
      keywords: [
        'dark chocolate', 'milk chocolate', 'white chocolate', 'chocolate bar', 'truffles',
        'cocoa', 'gummy', 'gummies', 'candy', 'candies', 'licorice', 'jelly beans',
        'hard candy', 'lollipops', 'chewing gum', 'breath mints', 'marshmallows', 'caramel'
      ]
    },
    {
      high: 'Grocery',
      fine: 'Beverages, Water & Drink Mixes',
      keywords: [
        'sparkling water', 'mineral water', 'spring water', 'coconut water', 'tonic water',
        'club soda', 'ginger beer', 'ginger ale', 'root beer', 'cola', 'energy drink',
        'kombucha', 'fruit juice', 'orange juice', 'apple juice', 'cranberry juice',
        'lemonade', 'iced tea', 'oat milk', 'almond milk', 'soy milk', 'coconut milk',
        'evaporated milk', 'condensed milk', 'drink mix', 'hydration multiplier', 'liquid i.v.'
      ]
    },
    {
      high: 'Grocery',
      fine: 'Pantry, Cooking, Baking & Spices',
      keywords: [
        'olive oil', 'extra virgin', 'avocado oil', 'coconut oil', 'sesame oil', 'vegetable oil',
        'canola oil', 'ghee', 'balsamic vinegar', 'apple cider vinegar', 'rice vinegar',
        'soy sauce', 'tamari', 'fish sauce', 'oyster sauce', 'worcestershire', 'hot sauce',
        'sriracha', 'chili oil', 'ketchup', 'mustard', 'mayonnaise', 'bbq sauce', 'barbecue sauce',
        'salad dressing', 'pasta', 'spaghetti', 'penne', 'fettuccine', 'macaroni', 'lasagna',
        'orzo', 'couscous', 'ramen', 'udon', 'soba noodles', 'rice noodles', 'basmati rice',
        'jasmine rice', 'brown rice', 'sushi rice', 'arborio rice', 'quinoa', 'lentils',
        'chickpeas', 'garbanzo', 'black beans', 'kidney beans', 'pinto beans', 'refried beans',
        'almond flour', 'all-purpose flour', 'bread flour', 'coconut flour', 'tapioca',
        'cornstarch', 'baking powder', 'baking soda', 'active dry yeast', 'vanilla extract',
        'cocoa powder', 'chocolate chips', 'maple syrup', 'raw honey', 'manuka honey', 'agave',
        'monk fruit', 'stevia', 'erythritol', 'cane sugar', 'brown sugar', 'powdered sugar',
        'peanut butter', 'almond butter', 'cashew butter', 'sunflower butter', 'nutella',
        'hazelnut spread', 'strawberry jam', 'fruit preserves', 'marmalade', 'jelly',
        'sea salt', 'kosher salt', 'himalayan salt', 'black pepper', 'peppercorns',
        'garlic powder', 'onion powder', 'paprika', 'cumin', 'cinnamon', 'oregano', 'basil',
        'thyme', 'rosemary', 'cayenne', 'chili powder', 'curry powder', 'everything bagel',
        'seasoning', 'bouillon', 'bone broth', 'chicken broth', 'vegetable broth', 'stock',
        'canned tomatoes', 'crushed tomatoes', 'tomato paste', 'marinara', 'pasta sauce',
        'pesto', 'salsa', 'olives', 'capers', 'pickles', 'sauerkraut', 'kimchi',
        'canned tuna', 'canned salmon', 'sardines', 'anchovies', 'spam', 'soup', 'chowder'
      ]
    },
    {
      high: 'Grocery',
      fine: 'Fresh Produce, Dairy, Bakery & Meats',
      keywords: [
        'apples', 'honeycrisp', 'gala apples', 'fuji apples', 'bananas', 'strawberries',
        'blueberries', 'raspberries', 'blackberries', 'grapes', 'oranges', 'mandarins',
        'clementines', 'lemons', 'limes', 'grapefruit', 'avocado', 'avocados', 'mango',
        'pineapple', 'watermelon', 'cantaloupe', 'peaches', 'pears', 'plums', 'cherries',
        'tomatoes', 'potatoes', 'sweet potatoes', 'onions', 'shallots', 'fresh garlic',
        'carrots', 'celery', 'broccoli', 'cauliflower', 'spinach', 'kale', 'lettuce',
        'arugula', 'romaine', 'salad mix', 'cucumbers', 'bell peppers', 'jalapeno',
        'zucchini', 'mushrooms', 'asparagus', 'green beans', 'brussels sprouts', 'corn',
        'whole milk', 'skim milk', '2% milk', 'half and half', 'heavy cream', 'whipping cream',
        'sour cream', 'cream cheese', 'cottage cheese', 'greek yogurt', 'yogurt', 'kefir',
        'cheddar', 'mozzarella', 'parmesan', 'provolone', 'swiss cheese', 'feta', 'gouda',
        'brie', 'goat cheese', 'string cheese', 'salted butter', 'unsalted butter', 'Irish butter',
        'pasture raised eggs', 'large eggs', 'organic eggs', 'egg whites',
        'sourdough bread', 'whole wheat bread', 'white bread', 'brioche', 'bagels',
        'english muffins', 'croissants', 'pita bread', 'naan', 'flour tortillas', 'corn tortillas',
        'hamburger buns', 'hot dog buns', 'dinner rolls', 'muffins', 'baguette',
        'chicken breast', 'chicken thighs', 'chicken wings', 'rotisserie chicken', 'ground turkey',
        'turkey breast', 'ground beef', 'ribeye', 'sirloin', 'steak', 'beef roast', 'pork chops',
        'pork tenderloin', 'bacon', 'pork sausage', 'italian sausage', 'breakfast sausage',
        'hot dogs', 'deli ham', 'prosciutto', 'salami', 'pepperoni', 'salmon fillet', 'smoked salmon',
        'tilapia', 'cod', 'halibut', 'shrimp', 'scallops', 'crab', 'lobster', 'tofu', 'tempeh',
        'seitan', 'beyond meat', 'impossible burger', 'frozen pizza', 'frozen waffles',
        'frozen dumplings', 'gyoza', 'edamame', 'ice cream', 'gelato', 'sorbet', 'popsicles'
      ]
    },

    // 2. SPORTS & FITNESS
    {
      high: 'Sports & Fitness',
      fine: 'Gym, Weight Training & Fitness Gear',
      keywords: [
        'dumbbell', 'dumbbells', 'kettlebell', 'barbell', 'weight plates', 'weight bench',
        'resistance bands', 'exercise bands', 'pull up bar', 'chin up bar', 'push up',
        'ab roller', 'medicine ball', 'slam ball', 'battle rope', 'jump rope', 'speed rope',
        'lifting straps', 'wrist wraps', 'weightlifting belt', 'gym gloves', 'workout gloves',
        'gym bag', 'duffel bag', 'squat pad', 'grip strengthener', 'hand gripper',
        'ankle weights', 'weighted vest', 'plyo box', 'home gym', 'rowing machine',
        'exercise bike', 'treadmill', 'elliptical', 'stepper'
      ]
    },
    {
      high: 'Sports & Fitness',
      fine: 'Yoga, Pilates, Running & Recovery',
      keywords: [
        'yoga mat', 'yoga block', 'yoga strap', 'pilates', 'foam roller', 'massage gun',
        'massage ball', 'lacrosse ball', 'stretching strap', 'balance board', 'bosu',
        'exercise ball', 'stability ball', 'running belt', 'hydration vest', 'sweatband',
        'athletic tape', 'kinesiology tape', 'sports brace', 'knee compression'
      ]
    },
    {
      high: 'Sports & Fitness',
      fine: 'Racquet, Team, Golf & Ball Sports',
      keywords: [
        'pickleball', 'tennis racket', 'tennis balls', 'badminton', 'squash', 'ping pong',
        'table tennis', 'golf balls', 'golf clubs', 'golf tees', 'golf glove', 'putting mat',
        'basketball', 'soccer ball', 'shin guards', 'football', 'baseball', 'softball',
        'batting gloves', 'volleyball', 'hockey', 'lacrosse', 'boxing gloves', 'punching bag',
        'mouthguard', 'martial arts', 'jiu jitsu'
      ]
    },
    {
      high: 'Sports & Fitness',
      fine: 'Cycling, Swimming & Outdoor Sports',
      keywords: [
        'bike helmet', 'bicycle', 'cycling', 'bike lock', 'bike light', 'bike pump',
        'bike tire', 'inner tube', 'bike saddle', 'bike pedals', 'water bottle cage',
        'swim goggles', 'swim cap', 'kickboard', 'snorkel', 'fins', 'kayak', 'paddleboard',
        'camping tent', 'sleeping bag', 'camping pad', 'camping chair', 'hammock',
        'hiking poles', 'trekking poles', 'hiking backpack', 'hydration pack', 'climbing',
        'carabiner', 'ski goggles', 'snowboard', 'fishing rod', 'fishing tackle'
      ]
    },

    // 3. BEAUTY
    {
      high: 'Beauty',
      fine: 'Skincare & Facial Care',
      keywords: [
        'moisturizer', 'moisturizing cream', 'facial cleanser', 'face wash', 'micellar water',
        'hyaluronic acid', 'retinol', 'niacinamide', 'vitamin c serum', 'face serum',
        'eye cream', 'face mask', 'sheet mask', 'sunscreen', 'sunblock', 'spf 30', 'spf 50',
        'facial toner', 'exfoliant', 'salicylic acid', 'benzoyl peroxide', 'acne patch',
        'pimple patch', 'lip balm', 'aquaphor', 'cerave', 'cetaphil', 'la roche-posay',
        'neutrogena', 'eucerin', 'body lotion', 'hand cream', 'body butter', 'cuticle cream'
      ]
    },
    {
      high: 'Beauty',
      fine: 'Hair Care & Styling',
      keywords: [
        'shampoo', 'conditioner', 'dry shampoo', 'hair mask', 'leave-in conditioner',
        'argan oil', 'rosemary oil', 'hair oil', 'hair spray', 'hair mousse', 'pomade',
        'hair clay', 'styling gel', 'minoxidil', 'scalp massager', 'hair dryer', 'blow dryer',
        'curling iron', 'flat iron', 'hair straightener', 'detangling brush', 'hair brush',
        'hair clips', 'scrunchies', 'headband'
      ]
    },
    {
      high: 'Beauty',
      fine: 'Makeup, Cosmetics & Nails',
      keywords: [
        'mascara', 'eyeliner', 'eyeshadow', 'foundation makeup', 'liquid foundation',
        'concealer', 'tinted moisturizer', 'setting powder', 'setting spray', 'blush',
        'bronzer', 'lipstick', 'lip gloss', 'lip liner', 'lip stain', 'eyebrow pencil',
        'brow gel', 'makeup brush', 'makeup sponge', 'makeup remover', 'nail polish',
        'gel nail', 'cuticle oil', 'false eyelashes'
      ]
    },
    {
      high: 'Beauty',
      fine: 'Fragrance, Deodorant & Grooming',
      keywords: [
        'eau de parfum', 'eau de toilette', 'perfume', 'cologne', 'body mist', 'deodorant',
        'antiperspirant', 'razor blades', 'safety razor', 'shaving cream', 'shave gel',
        'aftershave', 'beard oil', 'beard trimmer', 'nose hair trimmer', 'epilator',
        'tweezers', 'nail clipper', 'manicure', 'body wash', 'shower gel', 'bath bomb',
        'epsom salt', 'bar soap'
      ]
    },

    // 4. CLOTHING
    {
      high: 'Clothing',
      fine: 'Tops, T-Shirts & Shirts',
      keywords: [
        't-shirt', 't-shirts', 'tee shirt', 'v-neck', 'crewneck', 'polo shirt',
        'button-down shirt', 'dress shirt', 'flannel shirt', 'oxford shirt', 'henley',
        'tank top', 'camisole', 'blouse', 'tunic', 'crop top', 'long sleeve shirt'
      ]
    },
    {
      high: 'Clothing',
      fine: 'Sweaters, Hoodies & Outerwear',
      keywords: [
        'hoodie', 'sweatshirt', 'pullover', 'cardigan', 'sweater', 'fleece jacket',
        'puffer jacket', 'down jacket', 'rain jacket', 'windbreaker', 'trench coat',
        'winter coat', 'parka', 'denim jacket', 'blazer', 'outerwear'
      ]
    },
    {
      high: 'Clothing',
      fine: 'Pants, Jeans, Shorts & Dresses',
      keywords: [
        'jeans', 'denim pants', 'chinos', 'khakis', 'dress pants', 'trousers', 'cargo pants',
        'sweatpants', 'joggers', 'leggings', 'yoga pants', 'athletic shorts', 'chino shorts',
        'board shorts', 'running shorts', 'skirt', 'maxi dress', 'sundress', "levi's"
      ]
    },
    {
      high: 'Clothing',
      fine: 'Underwear, Socks & Sleepwear',
      keywords: [
        'boxer briefs', 'boxers', 'underwear', 'panties', 'bralette', 'sports bra',
        'underwire bra', 'undershirt', 'ankle socks', 'crew socks', 'no-show socks',
        'wool socks', 'compression socks', 'dress socks', 'pajamas', 'pajama', 'sleepwear',
        'nightgown', 'bathrobe', 'thermal underwear', 'base layer'
      ]
    },
    {
      high: 'Clothing',
      fine: 'Shoes, Footwear & Accessories',
      keywords: [
        'running shoes', 'sneakers', 'walking shoes', 'tennis shoes', 'hiking boots',
        'ankle boots', 'work boots', 'chelsea boots', 'loafers', 'oxfords', 'sandals',
        'flip flops', 'slippers', 'moccasins', 'ballet flats', 'shoe insoles', 'shoelaces',
        'swim trunks', 'swimsuit', 'bikini', 'rash guard', 'leather belt', 'baseball cap',
        'beanie', 'sun hat', 'bucket hat', 'winter gloves', 'scarf', 'necktie', 'sunglasses',
        'wallet', 'handbag', 'crossbody bag', 'tote bag'
      ]
    },

    // 5. COMPUTING
    {
      high: 'Computing',
      fine: 'Laptops, Desktops & Tablets',
      keywords: [
        'laptop', 'macbook', 'chromebook', 'notebook pc', 'desktop computer', 'mini pc',
        'all-in-one pc', 'ipad', 'galaxy tab', 'tablet'
      ]
    },
    {
      high: 'Computing',
      fine: 'Monitors & Display Accessories',
      keywords: [
        'gaming monitor', '4k monitor', 'ultrawide monitor', 'lcd monitor', 'led monitor',
        'portable monitor', 'monitor arm', 'monitor stand', 'vesa mount', 'displayport'
      ]
    },
    {
      high: 'Computing',
      fine: 'Storage & Memory (SSD / RAM / Flash)',
      keywords: [
        'nvme', 'm.2 ssd', 'internal ssd', 'external ssd', 'portable ssd', 'solid state drive',
        'hard drive', 'external hdd', 'flash drive', 'usb drive', 'thumb drive', 'microsd',
        'microsdxc', 'sdxc', 'memory card', 'ddr4', 'ddr5', 'so-dimm', 'nas drive'
      ]
    },
    {
      high: 'Computing',
      fine: 'Keyboards, Mice & Input Devices',
      keywords: [
        'mechanical keyboard', 'wireless keyboard', 'ergonomic keyboard', 'keycaps',
        'gaming mouse', 'wireless mouse', 'vertical mouse', 'trackball', 'trackpad',
        'mouse pad', 'desk mat', 'wrist rest', 'webcam', 'drawing tablet', 'apple pencil', 'stylus'
      ]
    },
    {
      high: 'Computing',
      fine: 'Networking, Wi-Fi & Hubs',
      keywords: [
        'wi-fi router', 'wifi 6', 'wifi 7', 'mesh wifi', 'ethernet switch', 'network switch',
        'cable modem', 'ethernet cable', 'cat6', 'cat7', 'cat8', 'patch panel', 'wifi extender',
        'network adapter', 'usb-c hub', 'thunderbolt dock', 'docking station', 'kvm switch',
        'usb hub', 'thunderbolt 4', 'hdmi cable', 'hdmi switch', 'sata cable'
      ]
    },
    {
      high: 'Computing',
      fine: 'PC Components, Cooling & Printers',
      keywords: [
        'graphics card', 'geforce rtx', 'radeon rx', 'motherboard', 'ryzen', 'intel core',
        'cpu cooler', 'aio cooler', 'thermal paste', 'pc case', 'atx power supply',
        'case fan', 'ups battery backup', 'laser printer', 'inkjet printer', 'toner cartridge',
        'ink cartridge', 'thermal label printer', 'document scanner', '3d printer', 'filament'
      ]
    },

    // 6. ELECTRONICS
    {
      high: 'Electronics',
      fine: 'Headphones, Earbuds & Audio',
      keywords: [
        'headphones', 'earbuds', 'airpods', 'noise cancelling', 'in-ear monitors',
        'bluetooth speaker', 'portable speaker', 'soundbar', 'subwoofer', 'bookshelf speakers',
        'condenser microphone', 'usb microphone', 'audio interface', 'headphone amp'
      ]
    },
    {
      high: 'Electronics',
      fine: 'Phones, Chargers & Batteries',
      keywords: [
        'iphone', 'samsung galaxy', 'google pixel', 'phone case', 'magsafe',
        'screen protector', 'tempered glass', 'wireless charger', 'car charger',
        'power bank', 'portable charger', 'phone mount', 'aa batteries', 'aaa batteries',
        '9v batteries', 'cr2032', 'alkaline batteries', 'rechargeable batteries', 'eneloop',
        'battery charger', 'gan charger', 'usb-c charger', 'wall charger', 'surge protector',
        'power strip', 'extension cord'
      ]
    },
    {
      high: 'Electronics',
      fine: 'Smart Home, TV & Cameras',
      keywords: [
        'smart plug', 'smart switch', 'smart bulb', 'echo dot', 'echo show', 'fire tv',
        'roku', 'apple tv', 'chromecast', 'security camera', 'blink camera', 'ring doorbell',
        'video doorbell', 'smart lock', 'smart thermostat', 'digital camera', 'mirrorless camera',
        'action camera', 'gopro', 'camera tripod', 'sd card reader', 'airtag', 'tile tracker'
      ]
    },

    // 7. HEALTH & WELLNESS
    {
      high: 'Health & Wellness',
      fine: 'Vitamins & Dietary Supplements',
      keywords: [
        'multivitamin', 'vitamin d3', 'vitamin d', 'vitamin c', 'vitamin b12', 'b-complex',
        'vitamin k2', 'omega-3', 'fish oil', 'magnesium glycinate', 'magnesium citrate',
        'zinc picolinate', 'probiotic', 'prebiotic', 'collagen peptides', 'creatine',
        'whey protein', 'plant protein', 'protein powder', 'electrolyte powder', 'ashwagandha',
        'melatonin', 'l-theanine', 'turmeric curcumin', 'biotin', 'coq10', 'glucosamine',
        'elderberry', 'iron supplement', 'folate', 'capsules', 'softgels', 'dietary supplement',
        'nutricost', 'nature made', 'thorne'
      ]
    },
    {
      high: 'Health & Wellness',
      fine: 'Medicine, First Aid & Personal Care',
      keywords: [
        'ibuprofen', 'acetaminophen', 'naproxen', 'aspirin', 'allergy medicine', 'cetirizine',
        'loratadine', 'fexofenadine', 'fluticasone', 'nasal spray', 'cough drops',
        'throat lozenges', 'antacid', 'famotidine', 'omeprazole', 'hydrocortisone',
        'antibiotic ointment', 'band-aid', 'adhesive bandages', 'gauze', 'first aid kit',
        'alcohol prep pads', 'digital thermometer', 'blood pressure monitor', 'pulse oximeter',
        'heating pad', 'ice pack', 'electric toothbrush', 'toothbrush heads', 'toothpaste',
        'dental floss', 'floss picks', 'water flosser', 'mouthwash', 'whitening strips',
        'eye drops', 'contact lens solution', 'reading glasses', 'ear plugs', 'nasal strips'
      ]
    },
    {
      high: 'Health & Wellness',
      fine: 'Household Cleaning & Paper Goods',
      keywords: [
        'toilet paper', 'bath tissue', 'paper towels', 'facial tissues', 'kleenex',
        'flushable wipes', 'trash bags', 'garbage bags', 'laundry detergent', 'tide pods',
        'fabric softener', 'dryer sheets', 'stain remover', 'oxiclean', 'dish soap',
        'dawn platinum', 'dishwasher pacs', 'cascade', 'rinse aid', 'disinfecting wipes',
        'all-purpose cleaner', 'glass cleaner', 'bathroom cleaner', 'toilet bowl cleaner',
        'scrub daddy', 'kitchen sponges', 'microfiber cleaning'
      ]
    },

    // 8. HOME & KITCHEN
    {
      high: 'Home & Kitchen',
      fine: 'Water & Air Filters',
      keywords: [
        'water filter', 'filter cartridge', 'whole house', 'spun poly', 'sediment filter',
        'carbon block', 'culligan', 'brita', 'zerowater', 'pur filter', 'reverse osmosis',
        'refrigerator filter', 'air purifier', 'hepa filter', 'furnace filter', 'ac filter',
        'merv', 'humidifier', 'dehumidifier'
      ]
    },
    {
      high: 'Home & Kitchen',
      fine: 'Kitchen Appliances, Cookware & Storage',
      keywords: [
        'air fryer', 'toaster oven', 'instant pot', 'pressure cooker', 'slow cooker',
        'rice cooker', 'blender', 'vitamix', 'food processor', 'stand mixer', 'hand mixer',
        'electric kettle', 'coffee maker', 'espresso machine', 'coffee grinder', 'milk frother',
        'waffle maker', 'vacuum sealer', 'kitchen scale', 'meat thermometer', 'frying pan',
        'skillet', 'cast iron', 'nonstick pan', 'stainless steel pan', 'saucepan', 'dutch oven',
        'stockpot', 'baking sheet', 'cutting board', 'chef knife', 'paring knife', 'spatula',
        'whisk', 'tongs', 'vegetable peeler', 'can opener', 'colander', 'mixing bowls',
        'measuring cups', 'oven mitts', 'water bottle', 'insulated tumbler', 'hydro flask',
        'stanley quencher', 'yeti rambler', 'travel mug', 'coffee mug', 'wine glasses',
        'food storage containers', 'meal prep containers', 'ziploc', 'freezer bags',
        'sandwich bags', 'aluminum foil', 'parchment paper', 'plastic wrap'
      ]
    },
    {
      high: 'Home & Kitchen',
      fine: 'Bedding, Bath, Organization & Vacuums',
      keywords: [
        'bed sheets', 'sheet set', 'fitted sheet', 'pillowcases', 'bed pillow',
        'memory foam pillow', 'down comforter', 'duvet cover', 'weighted blanket',
        'throw blanket', 'mattress protector', 'mattress topper', 'bath towels', 'hand towels',
        'washcloths', 'shower curtain', 'bath mat', 'blackout curtains', 'storage bins',
        'clothes hangers', 'laundry hamper', 'drying rack', 'shoe rack', 'drawer organizer',
        'command hooks', 'trash can', 'cordless vacuum', 'robot vacuum', 'steam mop', 'swiffer'
      ]
    },

    // 9. TOOLS & HOME IMPROVEMENT
    {
      high: 'Tools & Home Improvement',
      fine: 'Tools, Hardware, Electrical & Plumbing',
      keywords: [
        'cordless drill', 'impact driver', 'drill bits', 'screwdriver set', 'wrench set',
        'socket set', 'torque wrench', 'pliers', 'wire stripper', 'claw hammer', 'allen wrench',
        'hex key', 'utility knife', 'box cutter', 'multi-tool', 'circular saw', 'jigsaw',
        'tape measure', 'laser level', 'stud finder', 'digital caliper', 'multimeter',
        'soldering iron', 'flashlight', 'headlamp', 'wood screws', 'drywall anchors',
        'cable ties', 'zip ties', 'duct tape', 'electrical tape', 'painter tape',
        'mounting tape', 'teflon tape', 'super glue', 'wood glue', 'gorilla glue', 'epoxy',
        'silicone caulk', 'wd-40', 'sandpaper', 'padlock', 'bungee cords', 'shower head',
        'kitchen faucet', 'bidet', 'toilet flapper', 'plunger', 'drain snake', 'garden hose',
        'led light bulbs', 'dimmer switch', 'wall outlet', 'smoke detector'
      ]
    },

    // 10. PET SUPPLIES
    {
      high: 'Pet Supplies',
      fine: 'Dog & Cat Food, Treats & Care',
      keywords: [
        'dog food', 'dog treats', 'poop bags', 'dog leash', 'dog collar', 'dog harness',
        'dog bed', 'dog toy', 'dental chews', 'cat food', 'cat litter', 'litter genie',
        'cat treats', 'cat tree', 'scratching post', 'catnip', 'flea and tick', 'pet shampoo',
        'aquarium', 'fish food', 'bird seed'
      ]
    },

    // 11. OFFICE & SCHOOL
    {
      high: 'Office & School',
      fine: 'Stationery, Paper & Shipping Supplies',
      keywords: [
        'copy paper', 'printer paper', 'spiral notebook', 'composition book', 'legal pad',
        'sticky notes', 'post-it', 'index cards', 'ballpoint pens', 'gel pens', 'fountain pen',
        'sharpie', 'permanent marker', 'highlighters', 'dry erase', 'mechanical pencils',
        '3-ring binder', 'file folders', 'sheet protectors', 'laminating pouches', 'stapler',
        'paper clips', 'binder clips', 'packing tape', 'bubble mailers', 'shipping boxes',
        'mailing labels', 'envelopes'
      ]
    },

    // 12. BOOKS & MEDIA
    {
      high: 'Books & Media',
      fine: 'Books, Movies & Video Games',
      keywords: [
        'hardcover', 'paperback', 'kindle edition', 'audiobook', 'textbook',
        'playstation 5', 'ps5', 'xbox', 'nintendo switch', 'joy-con', 'blu-ray',
        'vinyl record', 'board game', 'card game', 'jigsaw puzzle'
      ]
    },

    // 13. BABY & TOYS
    {
      high: 'Baby & Toys',
      fine: 'Baby Care, Diapers & Toys',
      keywords: [
        'diapers', 'pampers', 'huggies', 'baby wipes', 'diaper rash', 'baby bottle',
        'pacifier', 'breast pump', 'baby monitor', 'stroller', 'car seat', 'baby carrier',
        'swaddle', 'onesies', 'lego', 'building blocks', 'action figure', 'plush toy',
        'stuffed animal', 'nerf', 'play-doh', 'crayola', 'coloring book'
      ]
    },

    // 14. AUTOMOTIVE
    {
      high: 'Automotive',
      fine: 'Car Care, Filters & Parts',
      keywords: [
        'motor oil', 'synthetic oil', 'oil filter', 'cabin air filter', 'engine air filter',
        'wiper blades', 'windshield wiper', 'jump starter', 'tire inflator',
        'tire pressure gauge', 'car wash', 'car wax', 'ceramic coating', 'obd2',
        'floor mats', 'dash cam', 'spark plugs', 'brake pads'
      ]
    }
  ];

  function escapeRegex(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  /**
   * Matches a keyword against text using word boundaries so short tokens never false-positive
   * inside unrelated words.
   */
  function hasKeywordBoundaryMatch(text, kw) {
    const trimmed = kw.trim().toLowerCase();
    if (!trimmed) return false;
    const pattern = new RegExp(`(?:^|[^a-z0-9])${escapeRegex(trimmed)}(?:s|es)?(?:$|[^a-z0-9])`, 'i');
    return pattern.test(text);
  }

  const KEYWORD_STOP_WORDS = new Set([
    'a', 'an', 'the', 'and', 'or', 'for', 'with', 'without', 'from', 'into', 'onto',
    'by', 'in', 'on', 'of', 'to', 'at', 'as', 'is', 'it', 'its', 'be', 'are', 'was',
    'were', 'that', 'this', 'these', 'those', 'your', 'our', 'new', 'all', 'per',
    'pack', 'count', 'ct', 'pk', 'set', 'piece', 'pieces', 'pcs', 'size', 'color',
    'edition', 'version', 'model', 'series', 'style', 'type', 'compatible',
    'return', 'window', 'closed', 'eligible', 'through', 'replacement', 'ordered',
    'sold', 'shipped', 'amazon', 'com', 'services', 'llc', 'inc', 'auto', 'delivered'
  ]);

  /**
   * Extracts meaningful word tokens and multi-word phrases from an item's title and metadata lines
   * so the user and agent can inspect the exact keywords available on each item.
   */
  function extractKeywordsFromText(title, metadataLines = []) {
    const tokenize = (str) => {
      const cleaned = cleanText(str || '')
        .toLowerCase()
        .replace(/['’]/g, '')
        .replace(/[^a-z0-9+&-]+/g, ' ');
      const rawTokens = cleaned.split(/\s+/).filter(Boolean);
      const out = [];
      for (const tok of rawTokens) {
        if (tok.length < 2) continue;
        if (/^\d+$/.test(tok)) continue;
        if (KEYWORD_STOP_WORDS.has(tok)) continue;
        if (!out.includes(tok)) out.push(tok);
      }
      return out;
    };

    const titleKeywords = tokenize(title || '');
    const metadataKeywords = tokenize((metadataLines || []).join(' '));
    const allExtractedKeywords = Array.from(new Set([...titleKeywords, ...metadataKeywords]));

    return {
      titleKeywords,
      metadataKeywords,
      allExtractedKeywords
    };
  }

  /**
   * Classifies a product into { highLevelCategory, fineGrainCategory, confidence, ...diagnostics }.
   * Any item that cannot be categorized with confidence is assigned to "UNCATEGORIZED".
   */
  function categorizeProduct(title, metadataLines = [], customRules = []) {
    const inspectedText = cleanText(`${title || ''} ${(metadataLines || []).join(' ')}`);
    const combined = ` ${inspectedText} `.toLowerCase();
    const { titleKeywords, metadataKeywords, allExtractedKeywords } = extractKeywordsFromText(
      title,
      metadataLines
    );

    if (!inspectedText) {
      return {
        highLevelCategory: 'UNCATEGORIZED',
        fineGrainCategory: 'Uncategorized',
        confidence: 'low',
        classificationSource: 'Empty Title/Metadata → UNCATEGORIZED',
        ruleScore: 0,
        matchedKeywords: [],
        candidateCategoryMatches: [],
        inspectedText: '',
        titleKeywords: [],
        metadataKeywords: [],
        allExtractedKeywords: []
      };
    }

    // 1. Check user-defined custom keyword rules first
    if (Array.isArray(customRules)) {
      for (const r of customRules) {
        if (r && r.keyword && combined.includes(r.keyword.toLowerCase())) {
          const matchedKw = r.keyword.toLowerCase();
          return {
            highLevelCategory: r.highLevelCategory || 'UNCATEGORIZED',
            fineGrainCategory: r.fineGrainCategory || r.highLevelCategory || 'Custom',
            confidence: 'high',
            classificationSource: `Custom Keyword Rule ("${r.keyword}")`,
            ruleScore: 100,
            matchedKeywords: [matchedKw],
            candidateCategoryMatches: [
              {
                highLevelCategory: r.highLevelCategory || 'UNCATEGORIZED',
                fineGrainCategory: r.fineGrainCategory || r.highLevelCategory || 'Custom',
                score: 100,
                matchedKeywords: [matchedKw]
              }
            ],
            inspectedText,
            titleKeywords,
            metadataKeywords,
            allExtractedKeywords
          };
        }
      }
    }

    let bestRule = null;
    let bestScore = 0;
    let bestMatchedKeywords = [];
    const candidateCategoryMatches = [];

    for (const rule of CATEGORY_TAXONOMY) {
      let score = 0;
      const ruleMatchedKws = [];
      for (const kw of rule.keywords) {
        if (hasKeywordBoundaryMatch(combined, kw)) {
          const isMultiWord = kw.trim().includes(' ');
          score += isMultiWord ? 12 + kw.length : 7 + Math.min(6, kw.length);
          ruleMatchedKws.push(kw);
        }
      }
      if (score > 0) {
        candidateCategoryMatches.push({
          highLevelCategory: rule.high,
          fineGrainCategory: rule.fine,
          score,
          matchedKeywords: ruleMatchedKws
        });
      }
      if (score > bestScore) {
        bestScore = score;
        bestRule = rule;
        bestMatchedKeywords = ruleMatchedKws;
      }
    }

    candidateCategoryMatches.sort((a, b) => b.score - a.score);

    // Confidence threshold: require at least one solid match (score >= 7)
    if (bestRule && bestScore >= 7) {
      return {
        highLevelCategory: bestRule.high,
        fineGrainCategory: bestRule.fine,
        confidence: bestScore >= 14 ? 'high' : 'medium',
        classificationSource: `Taxonomy Keyword Match (score: ${bestScore})`,
        ruleScore: bestScore,
        matchedKeywords: bestMatchedKeywords,
        candidateCategoryMatches: candidateCategoryMatches.slice(0, 5),
        inspectedText,
        titleKeywords,
        metadataKeywords,
        allExtractedKeywords
      };
    }

    // Secondary high-confidence domain signals:
    // Food / Grocery signals (nutrition/dietary food qualifiers paired with food weights/packs)
    const groceryDomainMatch = combined.match(
      /\b(usda\s+organic|non-gmo|whole\s+grain|kosher|vegan\s+food|gluten-free|sugar-free\s+syrup|nutrition\s+facts|edible|roast\s+coffee|tea\s+leaves)\b/i
    );
    const groceryWeightMatch = combined.match(
      /\b(\d+(?:\.\d+)?\s*(?:oz|fl\s*oz|lb|lbs|g|kg)\b|pack\s+of\s+\d+)/i
    );
    if (groceryDomainMatch && groceryWeightMatch) {
      const domainKws = [groceryDomainMatch[1].toLowerCase(), groceryWeightMatch[1].toLowerCase()];
      return {
        highLevelCategory: 'Grocery',
        fineGrainCategory: 'Pantry & Specialty Food',
        confidence: 'medium',
        classificationSource: 'Domain Signal: Grocery / Food Qualifier + Weight',
        ruleScore: 10,
        matchedKeywords: domainKws,
        candidateCategoryMatches: candidateCategoryMatches.slice(0, 5),
        inspectedText,
        titleKeywords,
        metadataKeywords,
        allExtractedKeywords
      };
    }

    // Sports & Fitness signals
    const sportsDomainMatch = combined.match(
      /\b(athletic|workout|fitness|exercise|gym|training\s+gear|sports\s+equipment)\b/i
    );
    if (sportsDomainMatch) {
      const sportKw = [sportsDomainMatch[1].toLowerCase()];
      return {
        highLevelCategory: 'Sports & Fitness',
        fineGrainCategory: 'Sports & Athletic Gear',
        confidence: 'medium',
        classificationSource: 'Domain Signal: Sports & Fitness Qualifier',
        ruleScore: 10,
        matchedKeywords: sportKw,
        candidateCategoryMatches: candidateCategoryMatches.slice(0, 5),
        inspectedText,
        titleKeywords,
        metadataKeywords,
        allExtractedKeywords
      };
    }

    // Anything that cannot be categorized with confidence goes to UNCATEGORIZED
    return {
      highLevelCategory: 'UNCATEGORIZED',
      fineGrainCategory: 'Uncategorized',
      confidence: 'low',
      classificationSource: 'Fallback (UNCATEGORIZED — no confident keyword match)',
      ruleScore: bestScore,
      matchedKeywords: bestMatchedKeywords,
      candidateCategoryMatches: candidateCategoryMatches.slice(0, 5),
      inspectedText,
      titleKeywords,
      metadataKeywords,
      allExtractedKeywords
    };
  }

  /**
   * Checks whether a date string falls in the "end of Nov and early Dec" holiday shopping window
   * (Nov 18 – Dec 18).
   */
  function isLateNovEarlyDecDate(dateStr) {
    if (!dateStr) return false;
    const d = new Date(cleanText(dateStr));
    if (isNaN(d.getTime())) return false;
    const month = d.getMonth(); // 0-indexed: 10 = Nov, 11 = Dec
    const day = d.getDate();
    if (month === 10 && day >= 18) return true; // End of Nov (Nov 18 - Nov 30)
    if (month === 11 && day <= 18) return true; // Early Dec (Dec 1 - Dec 18)
    return false;
  }

  /**
   * Detects large sets of purchases at the end of Nov and early Dec and categorizes them
   * as "Christmas Presents" (preserving their underlying product category inside fineGrainCategory).
   * Respects any user-customized cached overrides (`isUserCustomized === true`).
   */
  function applyHolidayGiftCategorization(orders, options = {}) {
    if (!Array.isArray(orders) || orders.length === 0) return orders;
    const minHolidayPurchaseSetSize =
      typeof options.minHolidayPurchaseSetSize === 'number' ? options.minHolidayPurchaseSetSize : 3;

    // Find all orders placed in late Nov / early Dec
    const holidayWindowOrders = orders.filter((o) => isLateNovEarlyDecDate(o.datePlaced || o.isoDate));
    const totalHolidayItems = holidayWindowOrders.reduce(
      (sum, o) => sum + ((o.items && o.items.length) || 1),
      0
    );

    const isLargeHolidaySet =
      totalHolidayItems >= minHolidayPurchaseSetSize ||
      holidayWindowOrders.length >= minHolidayPurchaseSetSize;

    for (const order of orders) {
      const inWindow = isLateNovEarlyDecDate(order.datePlaced || order.isoDate);
      const orderItemCount = (order.items && order.items.length) || 1;
      order.isHolidayWindow = inWindow;

      if (!inWindow) continue;
      if (!isLargeHolidaySet && orderItemCount < minHolidayPurchaseSetSize) continue;

      const markOrderAsGift = [];
      for (const shipment of order.shipments || []) {
        for (const item of shipment.items || []) {
          if (item.isUserCustomized) continue;
          // Skip recurring auto-delivered household subscriptions unless part of a multi-item gift order
          if (item.autoDelivered && orderItemCount === 1) continue;

          const baseHigh = item.baseHighCategory || item.highLevelCategory || 'UNCATEGORIZED';
          const baseFine = item.baseFineCategory || item.fineGrainCategory || 'Uncategorized';
          item.baseHighCategory = baseHigh;
          item.baseFineCategory = baseFine;
          item.highLevelCategory = 'Christmas Presents';
          item.fineGrainCategory =
            baseHigh !== 'UNCATEGORIZED'
              ? `Holiday Gift • ${baseHigh} (${baseFine})`
              : 'Holiday Gift (Late Nov / Early Dec)';
          markOrderAsGift.push(true);
        }
      }

      for (const item of order.items || []) {
        if (item.isUserCustomized) continue;
        if (item.autoDelivered && orderItemCount === 1) continue;

        const baseHigh = item.baseHighCategory || item.highLevelCategory || 'UNCATEGORIZED';
        const baseFine = item.baseFineCategory || item.fineGrainCategory || 'Uncategorized';
        item.baseHighCategory = baseHigh;
        item.baseFineCategory = baseFine;
        item.highLevelCategory = 'Christmas Presents';
        item.fineGrainCategory =
          baseHigh !== 'UNCATEGORIZED'
            ? `Holiday Gift • ${baseHigh} (${baseFine})`
            : 'Holiday Gift (Late Nov / Early Dec)';
      }

      if (markOrderAsGift.length > 0 && !order.isUserCustomized) {
        order.highLevelCategory = 'Christmas Presents';
        order.fineGrainCategory =
          (order.items[0] && order.items[0].fineGrainCategory) || 'Holiday Gift (Late Nov / Early Dec)';
      }
    }

    return orders;
  }

  /**
   * Builds a map of { [highLevelCategory]: string[] } containing all default taxonomy
   * sub-categories, any user-saved custom sub-categories, and any sub-categories present
   * on the orders — with every list sorted alphabetically.
   */
  function buildSubCategoriesByHigh(customSubCategoriesByHigh = {}, orders = [], customRules = []) {
    const map = {};

    const ensureAdd = (high, fine) => {
      const h = cleanText(high);
      const f = cleanText(fine);
      if (!h) return;
      if (!map[h]) map[h] = [];
      if (f && !map[h].includes(f)) map[h].push(f);
    };

    for (const h of DEFAULT_HIGH_CATEGORIES) {
      map[h] = [];
    }

    for (const rule of CATEGORY_TAXONOMY) {
      ensureAdd(rule.high, rule.fine);
    }

    // Default sub-categories for Christmas Presents and UNCATEGORIZED
    [
      'Holiday Gift (Late Nov / Early Dec)',
      'Holiday Gift • Baby & Toys',
      'Holiday Gift • Beauty',
      'Holiday Gift • Books & Media',
      'Holiday Gift • Clothing',
      'Holiday Gift • Computing',
      'Holiday Gift • Electronics',
      'Holiday Gift • Grocery',
      'Holiday Gift • Home & Kitchen',
      'Holiday Gift • Sports & Fitness',
      'Stocking Stuffer'
    ].forEach((f) => ensureAdd('Christmas Presents', f));

    ['Uncategorized', 'General Merchandise', 'Replacement Part'].forEach((f) =>
      ensureAdd('UNCATEGORIZED', f)
    );

    if (customSubCategoriesByHigh && typeof customSubCategoriesByHigh === 'object') {
      for (const [high, fineList] of Object.entries(customSubCategoriesByHigh)) {
        if (Array.isArray(fineList)) {
          for (const f of fineList) ensureAdd(high, f);
        }
      }
    }

    if (Array.isArray(customRules)) {
      for (const r of customRules) {
        if (r && (r.high || r.highLevelCategory)) {
          ensureAdd(r.high || r.highLevelCategory, r.fine || r.fineGrainCategory || r.high || r.highLevelCategory);
        }
      }
    }

    for (const o of orders || []) {
      ensureAdd(o.highLevelCategory, o.fineGrainCategory);
      for (const it of o.items || []) {
        ensureAdd(it.highLevelCategory, it.fineGrainCategory);
      }
      for (const s of o.shipments || []) {
        for (const it of s.items || []) {
          ensureAdd(it.highLevelCategory, it.fineGrainCategory);
        }
      }
    }

    for (const h of Object.keys(map)) {
      if (map[h].length === 0) {
        map[h] = [h === 'UNCATEGORIZED' ? 'Uncategorized' : `General ${h}`];
      }
      map[h] = sortAlpha(map[h]);
    }

    return map;
  }

  /**
   * Applies cached categorizations from prior runs (`cacheData.itemCategories` and `cacheData.customRules`)
   * to a list of parsed orders, and returns the updated cache entries to persist back to storage.
   */
  function applyAndSyncCategoryCache(orders, cacheData = {}) {
    const itemCategories = { ...(cacheData.itemCategories || {}) };
    const customRules = Array.isArray(cacheData.customRules) ? cacheData.customRules : [];

    // First pass: apply user overrides or cached categorizations
    for (const order of orders || []) {
      const allItemRefs = [];
      for (const s of order.shipments || []) {
        for (const it of s.items || []) allItemRefs.push(it);
      }
      for (const it of order.items || []) allItemRefs.push(it);

      for (const it of allItemRefs) {
        const orderItemKey = `order:${order.orderId}:${it.asin || normalizeTitleKey(it.title)}`;
        const asinKey = it.asin ? `asin:${it.asin}` : '';
        const titleKey = `title:${normalizeTitleKey(it.title)}`;

        const cachedOverride =
          itemCategories[orderItemKey] ||
          (asinKey && itemCategories[asinKey]) ||
          itemCategories[titleKey];

        if (cachedOverride && cachedOverride.isUserCustomized) {
          it.highLevelCategory = cachedOverride.highLevelCategory;
          it.fineGrainCategory = cachedOverride.fineGrainCategory;
          it.isUserCustomized = true;
          continue;
        }

        // Re-evaluate with custom rules if any exist
        if (customRules.length > 0) {
          const rNorm = customRules.map((r) => ({
            keyword: r.keyword,
            highLevelCategory: r.highLevelCategory || r.high,
            fineGrainCategory: r.fineGrainCategory || r.fine || r.highLevelCategory || r.high
          }));
          const cat = categorizeProduct(it.title, it.metadataLines, rNorm);
          it.highLevelCategory = cat.highLevelCategory;
          it.fineGrainCategory = cat.fineGrainCategory;
        }

        // If prior run had a cached category and current is UNCATEGORIZED, use cached
        if (
          cachedOverride &&
          cachedOverride.highLevelCategory &&
          it.highLevelCategory === 'UNCATEGORIZED'
        ) {
          it.highLevelCategory = cachedOverride.highLevelCategory;
          it.fineGrainCategory = cachedOverride.fineGrainCategory || 'Uncategorized';
        }
      }
    }

    // Second pass: apply late-Nov / early-Dec Christmas Presents rule across the full order set
    applyHolidayGiftCategorization(orders);

    // Third pass: sync order-level summary and write back to itemCategories + subCategoriesByHigh cache
    for (const order of orders || []) {
      if (order.items && order.items[0]) {
        order.highLevelCategory = order.items[0].highLevelCategory || 'UNCATEGORIZED';
        order.fineGrainCategory = order.items[0].fineGrainCategory || 'Uncategorized';
      }

      for (const it of order.items || []) {
        const orderItemKey = `order:${order.orderId}:${it.asin || normalizeTitleKey(it.title)}`;
        const asinKey = it.asin ? `asin:${it.asin}` : '';
        const titleKey = `title:${normalizeTitleKey(it.title)}`;

        const entry = {
          highLevelCategory: it.highLevelCategory || 'UNCATEGORIZED',
          fineGrainCategory: it.fineGrainCategory || 'Uncategorized',
          isUserCustomized: Boolean(it.isUserCustomized),
          updatedAt: Date.now()
        };

        itemCategories[orderItemKey] = entry;
        if (it.highLevelCategory !== 'Christmas Presents' || it.isUserCustomized) {
          if (asinKey) itemCategories[asinKey] = entry;
          if (titleKey) itemCategories[titleKey] = entry;
        }
      }
    }

    const subCategoriesByHigh = buildSubCategoriesByHigh(
      cacheData.subCategoriesByHigh || {},
      orders,
      customRules
    );
    const customHighCategories = sortAlpha([
      ...(cacheData.customHighCategories || []),
      ...Object.keys(subCategoriesByHigh)
    ]);

    cacheData.itemCategories = itemCategories;
    cacheData.customRules = customRules;
    cacheData.subCategoriesByHigh = subCategoriesByHigh;
    cacheData.customHighCategories = customHighCategories;

    return cacheData;
  }

  /**
   * Extracts Delivery Date & Delivery Status for an order/shipment.
   */
  function extractDeliveryDateInfo(statusPrimary, statusSecondary, metadataLines, datePlaced) {
    const lines = [statusPrimary, statusSecondary, ...(metadataLines || [])].filter(Boolean);
    const placedYearMatch = (datePlaced || '').match(/\b(20\d{2})\b/);
    const fallbackYear = placedYearMatch ? placedYearMatch[1] : String(new Date().getFullYear());

    for (const line of lines) {
      const m = line.match(
        /\b(Delivered|Arriving|Expected(?:\s+delivery)?|Shipped)\s+(?:on\s+|by\s+)?([A-Za-z]+\s+\d{1,2}(?:,\s*\d{4})?|today|yesterday|tomorrow|(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday))/i
      );
      if (m) {
        const verb = m[1];
        let datePart = cleanText(m[2]);
        if (/^[A-Za-z]+\s+\d{1,2}$/.test(datePart)) {
          datePart = `${datePart}, ${fallbackYear}`;
        }
        return {
          deliveryDate: datePart,
          deliveryDateIso: toIsoDate(datePart) || toIsoDate(datePlaced),
          deliveryStatus: `${verb} ${datePart}`,
          deliveryNote: statusSecondary && statusSecondary !== line ? statusSecondary : ''
        };
      }
    }

    for (const line of lines) {
      const rm = line.match(
        /\b(?:Return\s+window\s+closed\s+on|Eligible\s+for\s+return\s+through|Return\s+eligible\s+through)\s+([A-Za-z]+\s+\d{1,2}(?:,\s*\d{4})?)/i
      );
      if (rm) {
        let closeDateStr = cleanText(rm[1]);
        if (/^[A-Za-z]+\s+\d{1,2}$/.test(closeDateStr)) {
          closeDateStr = `${closeDateStr}, ${fallbackYear}`;
        }
        const closeDateObj = new Date(closeDateStr);
        if (!isNaN(closeDateObj.getTime())) {
          const estDelivObj = new Date(closeDateObj.getTime() - 30 * 24 * 60 * 60 * 1000);
          const placedObj = datePlaced ? new Date(datePlaced) : null;
          if (placedObj && !isNaN(placedObj.getTime()) && estDelivObj < placedObj) {
            estDelivObj.setTime(placedObj.getTime() + 2 * 24 * 60 * 60 * 1000);
          }
          const formattedEst = formatShortDate(estDelivObj);
          return {
            deliveryDate: `~${formattedEst}`,
            deliveryDateIso: toIsoDate(formattedEst),
            deliveryStatus: `Delivered ~${formattedEst}`,
            deliveryNote: line
          };
        }
      }
    }

    if (statusPrimary) {
      return {
        deliveryDate: statusPrimary,
        deliveryDateIso: toIsoDate(statusPrimary) || toIsoDate(datePlaced),
        deliveryStatus: statusPrimary,
        deliveryNote: statusSecondary || ''
      };
    }

    return {
      deliveryDate: datePlaced || 'Delivered',
      deliveryDateIso: toIsoDate(datePlaced),
      deliveryStatus: 'Completed',
      deliveryNote: ''
    };
  }

  function extractAccountName(doc) {
    const navLine = doc.querySelector('#nav-link-accountList-nav-line-1, #nav-link-accountList .nav-line-1');
    if (navLine) {
      const txt = cleanText(navLine.textContent);
      const m = txt.match(/(?:Hello|Hi),?\s+(.+)/i);
      if (m && m[1] && !/sign\s*in/i.test(m[1])) {
        return cleanText(m[1]);
      }
    }
    return '';
  }

  function findPageTimeFilterSelect(doc) {
    const direct = doc.querySelector(
      'select[name="timeFilter"], select#time-filter, select#orderFilter, .time-filter-section select, .js-time-filter-form select'
    );
    if (direct && direct.id !== 'amz-exp-year-select') return direct;

    const allSelects = Array.from(doc.querySelectorAll('select'));
    for (const sel of allSelects) {
      if (sel.id === 'amz-exp-year-select' || sel.id === 'searchDropdownBox') continue;
      const optTexts = Array.from(sel.options || []).map((o) => `${o.value} ${o.textContent}`.toLowerCase());
      const hasYearOrMonths = optTexts.some(
        (t) => /year-20\d{2}|\b202\d\b|months-3|past 3 months|last 30 days/i.test(t)
      );
      if (hasYearOrMonths) return sel;
    }
    return null;
  }

  function labelToTimeFilterValue(label) {
    const cleaned = cleanText(label).toLowerCase();
    if (!cleaned) return '';
    if (/^\d{4}$/.test(cleaned)) return `year-${cleaned}`;
    if (cleaned.includes('3 month')) return 'months-3';
    if (cleaned.includes('30 day')) return 'last30';
    if (cleaned.includes('archived')) return 'archived';
    const ym = cleaned.match(/\b(20\d{2}|19\d{2})\b/);
    if (ym) return `year-${ym[1]}`;
    return '';
  }

  function extractTimeFilters(doc, currentUrl) {
    const select = findPageTimeFilterSelect(doc);
    const options = [];
    let activeFilter = '';

    try {
      const urlObj = new URL(currentUrl || window.location.href);
      activeFilter = urlObj.searchParams.get('timeFilter') || urlObj.searchParams.get('orderFilter') || '';
    } catch (_) {}

    let visiblePromptText = '';
    const promptEls = Array.from(
      doc.querySelectorAll('.time-filter-section .a-dropdown-prompt, #time-filter + .a-button .a-dropdown-prompt, .a-dropdown-container .a-dropdown-prompt')
    );
    for (const pEl of promptEls) {
      if (pEl.closest('#amz-year-exporter-banner')) continue;
      const t = cleanText(pEl.textContent);
      if (t && (/^\d{4}$/.test(t) || /month|day|year|archived/i.test(t))) {
        visiblePromptText = t;
        break;
      }
    }

    if (select && select.options && select.options.length > 0) {
      for (const opt of Array.from(select.options)) {
        const val = cleanText(opt.value);
        const label = cleanText(opt.textContent);
        if (!val) continue;
        const isSelected =
          (activeFilter && val === activeFilter) ||
          (!activeFilter && visiblePromptText && label.toLowerCase() === visiblePromptText.toLowerCase()) ||
          (!activeFilter && !visiblePromptText && opt.selected);

        options.push({
          value: val,
          label: label || val.replace(/^year-/, ''),
          selected: Boolean(isSelected)
        });
        if (isSelected && !activeFilter) {
          activeFilter = val;
        }
      }
    }

    if (!activeFilter && visiblePromptText) {
      activeFilter = labelToTimeFilterValue(visiblePromptText);
    }

    if (options.length === 0) {
      const currentYear = new Date().getFullYear();
      options.push(
        { value: 'months-3', label: 'past 3 months', selected: activeFilter === 'months-3' },
        { value: 'last30', label: 'last 30 days', selected: activeFilter === 'last30' }
      );
      for (let y = currentYear; y >= currentYear - 10; y--) {
        const val = `year-${y}`;
        options.push({
          value: val,
          label: String(y),
          selected: val === activeFilter
        });
      }
    }

    if (!activeFilter) {
      const selectedOpt = options.find((o) => o.selected) || options[0];
      activeFilter = selectedOpt ? selectedOpt.value : `year-${new Date().getFullYear()}`;
    }

    options.forEach((o) => {
      o.selected = o.value === activeFilter;
    });

    return { activeFilter, options };
  }

  function extractTotalOrdersCount(doc) {
    const numSpan = doc.querySelector('.num-orders, .a-section .num-orders, label[for="time-filter"] .num-orders');
    if (numSpan) {
      const m = numSpan.textContent.replace(/,/g, '').match(/(\d+)/);
      if (m) return parseInt(m[1], 10);
    }
    const candidates = doc.querySelectorAll('.time-filter-section, .a-row, form, span, div');
    for (const el of Array.from(candidates)) {
      if (el.id === 'amz-year-exporter-banner' || el.closest('#amz-year-exporter-banner')) continue;
      if (el.children.length > 6) continue;
      const txt = cleanText(el.textContent);
      const m = txt.replace(/,/g, '').match(/\b(\d+)\s+orders?\s+placed\s+in\b/i);
      if (m) return parseInt(m[1], 10);
    }
    return null;
  }

  function findOrderCardElements(doc) {
    let cards = Array.from(doc.querySelectorAll('.order-card.js-order-card'));
    if (cards.length === 0) {
      cards = Array.from(doc.querySelectorAll('.js-order-card, .order-card, div.a-box-group.order'));
    }
    if (cards.length === 0) {
      const groups = Array.from(doc.querySelectorAll('.a-box-group'));
      cards = groups.filter((g) => {
        if (g.querySelector('.a-box-group')) return false;
        const txt = g.textContent || '';
        return (
          g.querySelector('.order-header, .yohtmlc-order-id') ||
          (/ORDER\s+PLACED/i.test(txt) && /ORDER\s*#/i.test(txt))
        );
      });
    }
    return cards;
  }

  function parseOrderHeader(cardEl, baseUrl) {
    const headerEl =
      cardEl.querySelector('.order-header, .a-box.a-color-offset-background, .order-info') ||
      cardEl.firstElementChild;

    let orderId = '';
    let datePlaced = '';
    let total = '';
    let shipTo = '';
    let shippingAddress = '';
    let orderDetailsUrl = '';
    let invoiceUrl = '';
    const headerLinks = [];

    const popovers = cardEl.querySelectorAll('.a-popover-preload');
    for (const pop of Array.from(popovers)) {
      const popText = cleanText(pop.textContent);
      if (popText && popText.length > 5 && popText.length < 350) {
        shippingAddress = popText;
        break;
      }
    }

    if (headerEl) {
      const orderIdEl = headerEl.querySelector(
        '.yohtmlc-order-id span[dir="ltr"], .yohtmlc-order-id bdi, .yohtmlc-order-id .a-color-secondary:last-child'
      );
      if (orderIdEl) {
        orderId = cleanText(orderIdEl.textContent).replace(/^ORDER\s*#\s*/i, '');
      }
      if (!orderId) {
        const idMatch =
          headerEl.textContent.match(/ORDER\s*#\s*([0-9A-Z-]{10,25})/i) ||
          headerEl.textContent.match(/\b(\d{3}-\d{7}-\d{7})\b/);
        if (idMatch) orderId = cleanText(idMatch[1]);
      }

      const cols = headerEl.querySelectorAll('.a-column, .order-header__header-list-item, li');
      for (const col of Array.from(cols)) {
        const colText = cleanText(col.textContent);
        if (/^ORDER\s+PLACED\b/i.test(colText) && !datePlaced) {
          const val = cleanText(colText.replace(/^ORDER\s+PLACED\s*/i, ''));
          if (val) datePlaced = val;
        } else if (/^TOTAL\b/i.test(colText) && !total) {
          const val = cleanText(colText.replace(/^TOTAL\s*/i, ''));
          if (val) total = val;
        } else if (/^SHIP\s+TO\b/i.test(colText) && !shipTo) {
          const recipientEl = col.querySelector('.yohtmlc-recipient a, .a-popover-trigger, a');
          if (recipientEl) {
            shipTo = cleanText(recipientEl.textContent);
          } else {
            const val = cleanText(colText.replace(/^SHIP\s+TO\s*/i, ''));
            if (val) shipTo = val;
          }
        }
      }

      const fullHeaderText = cleanText(headerEl.textContent);
      if (!datePlaced) {
        const dm =
          fullHeaderText.match(/ORDER\s+PLACED\s+([A-Za-z]+\s+\d{1,2},\s+\d{4}|\d{1,2}\s+[A-Za-z]+\s+\d{4})/i) ||
          fullHeaderText.match(/((?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2},\s+\d{4})/i);
        if (dm) datePlaced = cleanText(dm[1]);
      }
      if (!total) {
        const tm = fullHeaderText.match(/TOTAL\s+([$£€¥]\s*[\d,.]+|[\d,.]+\s*(?:USD|CAD|GBP|EUR))/i);
        if (tm) total = cleanText(tm[1]);
      }
      if (!shipTo) {
        const recEl = headerEl.querySelector('.yohtmlc-recipient a, .yohtmlc-recipient');
        if (recEl) {
          shipTo = cleanText(recEl.textContent.replace(/^SHIP\s+TO\s*/i, ''));
        } else {
          const sm = fullHeaderText.match(/SHIP\s+TO\s+(.+?)(?:\s+ORDER\s*#|$)/i);
          if (sm) shipTo = cleanText(sm[1]);
        }
      }

      const linkContainer =
        headerEl.querySelector('.yohtmlc-order-level-connections, .a-span-last, .a-text-right') || headerEl;
      const links = linkContainer.querySelectorAll('a[href]');
      for (const a of Array.from(links)) {
        if (a.closest('.a-popover-preload') || a.closest('.yohtmlc-recipient')) continue;
        const label = cleanText(a.textContent);
        const url = resolveUrl(a.getAttribute('href'), baseUrl);
        if (!label || !url || label === shipTo) continue;

        if (/order\s+details/i.test(label) && !orderDetailsUrl) {
          orderDetailsUrl = url;
        } else if (/invoice/i.test(label) && !invoiceUrl) {
          invoiceUrl = url;
        }
        if (!headerLinks.some((l) => l.label.toLowerCase() === label.toLowerCase())) {
          headerLinks.push({ label, url });
        }
      }
    }

    if (!orderDetailsUrl && orderId) {
      orderDetailsUrl = resolveUrl(
        `/gp/your-account/order-details?orderID=${encodeURIComponent(orderId)}`,
        baseUrl
      );
    }
    if (!invoiceUrl && orderId) {
      invoiceUrl = resolveUrl(
        `/gp/css/summary/print.html?orderID=${encodeURIComponent(orderId)}`,
        baseUrl
      );
    }

    const { amount: totalAmount, currency } = parseCurrencyAmount(total);

    return {
      orderId,
      datePlaced,
      isoDate: toIsoDate(datePlaced),
      total,
      totalAmount,
      currency,
      shipTo,
      shippingAddress,
      orderDetailsUrl,
      invoiceUrl,
      headerLinks
    };
  }

  function parseButtonElement(btnEl, baseUrl) {
    if (!btnEl || btnEl.closest('.a-popover-preload')) return null;
    const textEl = btnEl.querySelector('.a-button-text, a, span, input') || btnEl;
    const label = cleanText(textEl.textContent || textEl.getAttribute('value') || '');
    if (!label) return null;

    const linkEl = btnEl.tagName === 'A' ? btnEl : btnEl.querySelector('a[href]');
    const url = linkEl ? resolveUrl(linkEl.getAttribute('href'), baseUrl) : '';

    const isPrimary =
      btnEl.classList.contains('a-button-primary') ||
      Boolean(btnEl.querySelector('.a-button-primary')) ||
      /^(get\s+product\s+support|buy\s+it\s+again|track\s+package)$/i.test(label);

    const isBuyAgain = /buy\s+it\s+again/i.test(label);

    return {
      label,
      url,
      variant: isPrimary ? 'primary' : 'secondary',
      icon: isBuyAgain ? 'buy-again' : null
    };
  }

  function parseBoxOrShipment(boxEl, baseUrl, datePlaced) {
    let statusPrimary = '';
    let statusSecondary = '';

    const primaryEl = boxEl.querySelector(
      '.yohtmlc-shipment-status-primaryText, .delivery-box__primary-text, .a-size-medium.a-color-base.a-text-bold, h3.a-size-medium, h4.a-size-medium'
    );
    if (primaryEl && !primaryEl.closest('.yohtmlc-item')) {
      statusPrimary = cleanText(primaryEl.textContent);
    }

    const secondaryEl = boxEl.querySelector(
      '.yohtmlc-shipment-status-secondaryText, .delivery-box__secondary-text'
    );
    if (secondaryEl && !secondaryEl.closest('.yohtmlc-item')) {
      statusSecondary = cleanText(secondaryEl.textContent);
    }

    const actions = [];
    const seenActionLabels = new Set();

    const rightColCandidates = boxEl.querySelectorAll(
      '.yohtmlc-shipment-level-connections .a-button, .a-fixed-right-grid-col.a-col-right .a-button, .a-span4 .a-button, .a-span3 .a-button, ul.a-unordered-list .a-button'
    );

    for (const btn of Array.from(rightColCandidates)) {
      if (btn.closest('.yohtmlc-item, .item-box')) continue;
      const parsed = parseButtonElement(btn, baseUrl);
      if (parsed && !/buy\s+it\s+again|view\s+your\s+item/i.test(parsed.label)) {
        const key = parsed.label.toLowerCase();
        if (!seenActionLabels.has(key)) {
          seenActionLabels.add(key);
          actions.push(parsed);
        }
      }
    }

    if (actions.length === 0) {
      const allBtns = boxEl.querySelectorAll('.a-button');
      for (const btn of Array.from(allBtns)) {
        const parsed = parseButtonElement(btn, baseUrl);
        if (!parsed) continue;
        if (/^(buy\s+it\s+again|view\s+your\s+item|archive\s+order)$/i.test(parsed.label)) continue;
        const key = parsed.label.toLowerCase();
        if (!seenActionLabels.has(key)) {
          seenActionLabels.add(key);
          actions.push(parsed);
        }
      }
    }

    let itemEls = Array.from(boxEl.querySelectorAll('.yohtmlc-item, .item-box'));
    if (itemEls.length === 0) {
      const grids = Array.from(boxEl.querySelectorAll('.a-fixed-left-grid'));
      itemEls = grids.filter((g) => g.querySelector('img') && g.querySelector('a[href]'));
    }
    if (itemEls.length === 0) {
      if (boxEl.querySelector('img') && boxEl.querySelector('.yohtmlc-product-title, a[href*="/dp/"], a[href*="/gp/"]')) {
        itemEls = [boxEl];
      }
    }

    const items = [];
    for (const itemEl of itemEls) {
      const imgEl = itemEl.querySelector('img');
      let imageUrl = '';
      if (imgEl) {
        const currentSrc = imgEl.currentSrc || '';
        const src = imgEl.getAttribute('src') || '';
        const hires = imgEl.getAttribute('data-a-hires') || imgEl.getAttribute('data-src') || '';
        const chosen =
          currentSrc && !currentSrc.startsWith('data:image/gif')
            ? currentSrc
            : (!src || src.startsWith('data:image/gif')) && hires
              ? hires
              : src || hires;
        imageUrl = resolveUrl(chosen, baseUrl);
      }

      let quantity = 1;
      const qtyEl = itemEl.querySelector('.item-view-qty, .a-profile-avatar-badge, .yohtmlc-item-qty');
      if (qtyEl) {
        const qn = parseInt(cleanText(qtyEl.textContent), 10);
        if (!isNaN(qn) && qn > 0) quantity = qn;
      }

      let titleEl =
        itemEl.querySelector('.yohtmlc-product-title a, a.yohtmlc-product-title') ||
        itemEl.querySelector('.yohtmlc-product-title');
      let linkEl =
        titleEl && titleEl.tagName === 'A'
          ? titleEl
          : titleEl
            ? titleEl.closest('a[href]') || titleEl.querySelector('a[href]')
            : null;

      if (!linkEl) {
        linkEl = itemEl.querySelector('a[href*="/dp/"], a[href*="/gp/product/"], a[href*="/product/"]');
      }
      if (!titleEl && linkEl) {
        titleEl = linkEl;
      }

      const title = titleEl ? cleanText(titleEl.textContent) : '';
      const productUrl = linkEl ? resolveUrl(linkEl.getAttribute('href'), baseUrl) : '';
      const asin = extractAsinFromUrl(productUrl);

      const itemButtons = [];
      const seenItemBtn = new Set();
      const btnNodes = itemEl.querySelectorAll('.a-button');
      for (const bNode of Array.from(btnNodes)) {
        if (itemEl === boxEl && bNode.closest('.yohtmlc-shipment-level-connections')) continue;
        const parsedBtn = parseButtonElement(bNode, baseUrl);
        if (!parsedBtn) continue;
        if (seenActionLabels.has(parsedBtn.label.toLowerCase())) continue;
        const k = parsedBtn.label.toLowerCase();
        if (!seenItemBtn.has(k)) {
          seenItemBtn.add(k);
          itemButtons.push(parsedBtn);
        }
      }

      const metadataLines = [];
      let returnWindow = '';
      let autoDelivered = '';
      let itemPrice = '';

      const infoRoot =
        itemEl.querySelector('.a-fixed-left-grid-col.a-col-right, .a-col-right') || itemEl;

      const candidateRows = infoRoot.querySelectorAll('.a-row, .a-size-small, .a-size-base, .a-color-price, span, div');
      for (const node of Array.from(candidateRows)) {
        if (node.closest('.a-button, .a-popover-preload, .yohtmlc-shipment-level-connections')) continue;
        if (titleEl && (node === titleEl || node.contains(titleEl) || titleEl.contains(node))) continue;

        const hasBlockChild = Array.from(node.children).some((ch) =>
          ['DIV', 'P', 'UL', 'LI'].includes(ch.tagName) || ch.classList.contains('a-row')
        );
        if (hasBlockChild) continue;

        const line = cleanText(node.textContent);
        if (!line || line === title) continue;
        if (line === statusPrimary || line === statusSecondary) continue;
        if (seenItemBtn.has(line.toLowerCase()) || seenActionLabels.has(line.toLowerCase())) continue;

        if (/^[$£€¥]\s*\d+(?:\.\d{2})?$/.test(line) && !itemPrice) {
          itemPrice = line;
        }

        if (metadataLines.some((existing) => existing === line || existing.includes(line))) continue;
        const subIdx = metadataLines.findIndex((existing) => line.includes(existing) && line.length < 140);
        if (subIdx !== -1) {
          metadataLines[subIdx] = line;
        } else if (line.length < 220) {
          metadataLines.push(line);
        }

        if (/return\s+window|eligible\s+for\s+return|return\s+eligible|replacement\s+ordered/i.test(line)) {
          returnWindow = line;
        }
        if (/auto-delivered|subscribe\s*&\s*save/i.test(line)) {
          autoDelivered = line;
        }
      }

      const catResult = categorizeProduct(title, metadataLines);
      const { highLevelCategory, fineGrainCategory, confidence } = catResult;
      const deliveryInfo = extractDeliveryDateInfo(statusPrimary, statusSecondary, metadataLines, datePlaced);

      if (title || imageUrl) {
        items.push({
          title: title || 'Amazon Order Item',
          productUrl,
          asin,
          imageUrl,
          quantity,
          itemPrice,
          highLevelCategory,
          fineGrainCategory,
          baseHighCategory: highLevelCategory,
          baseFineCategory: fineGrainCategory,
          categoryConfidence: confidence,
          classificationSource: catResult.classificationSource,
          ruleScore: catResult.ruleScore,
          matchedKeywords: catResult.matchedKeywords,
          candidateCategoryMatches: catResult.candidateCategoryMatches,
          inspectedText: catResult.inspectedText,
          titleKeywords: catResult.titleKeywords,
          metadataKeywords: catResult.metadataKeywords,
          allExtractedKeywords: catResult.allExtractedKeywords,
          deliveryDate: deliveryInfo.deliveryDate,
          deliveryDateIso: deliveryInfo.deliveryDateIso,
          deliveryStatus: deliveryInfo.deliveryStatus,
          returnWindow,
          autoDelivered,
          metadataLines,
          itemButtons
        });
      }
    }

    const boxDeliveryInfo = extractDeliveryDateInfo(
      statusPrimary,
      statusSecondary,
      items[0] ? items[0].metadataLines : [],
      datePlaced
    );

    return {
      statusPrimary,
      statusSecondary,
      deliveryDate: boxDeliveryInfo.deliveryDate,
      deliveryDateIso: boxDeliveryInfo.deliveryDateIso,
      deliveryStatus: boxDeliveryInfo.deliveryStatus,
      actions,
      items
    };
  }

  function parseOrderCard(cardEl, baseUrl, pageNumber) {
    const header = parseOrderHeader(cardEl, baseUrl);

    let bodyBoxes = Array.from(
      cardEl.querySelectorAll('.delivery-box, .shipment, .a-box:not(.order-header):not(.a-color-offset-background):not(.order-info)')
    );
    bodyBoxes = bodyBoxes.filter((box) => !bodyBoxes.some((other) => other !== box && other.contains(box)));

    if (bodyBoxes.length === 0) {
      bodyBoxes = [cardEl];
    }

    const shipments = [];
    const allItems = [];

    for (const box of bodyBoxes) {
      const parsedBox = parseBoxOrShipment(box, baseUrl, header.datePlaced);
      if (parsedBox.items.length > 0 || parsedBox.actions.length > 0 || parsedBox.statusPrimary) {
        shipments.push(parsedBox);
        for (const it of parsedBox.items) {
          allItems.push({
            ...it,
            shipmentStatus: parsedBox.statusPrimary || it.deliveryStatus || '',
            shipmentActions: parsedBox.actions
          });
        }
      }
    }

    const primaryItem = allItems[0] || {};
    const primaryShipment = shipments[0] || {};
    const deliveryDate =
      primaryItem.deliveryDate ||
      primaryShipment.deliveryDate ||
      header.datePlaced ||
      'Delivered';
    const deliveryDateIso =
      primaryItem.deliveryDateIso ||
      primaryShipment.deliveryDateIso ||
      header.isoDate ||
      '';
    const highLevelCategory = primaryItem.highLevelCategory || 'UNCATEGORIZED';
    const fineGrainCategory = primaryItem.fineGrainCategory || 'Uncategorized';

    return {
      ...header,
      deliveryDate,
      deliveryDateIso,
      highLevelCategory,
      fineGrainCategory,
      pageNumber: pageNumber || 1,
      shipments,
      items: allItems
    };
  }

  function extractPaginationInfo(doc, currentUrl, currentPageNum, totalExpectedOrders) {
    let nextUrl = '';
    let maxPageNumber = currentPageNum || 1;
    let hasEnabledNext = false;
    let isNextExplicitlyDisabled = false;

    const paginationEl = doc.querySelector(
      '.a-pagination, ul.a-pagination, nav[aria-label*="pagination" i], .your-orders-pagination'
    );

    const candidates = paginationEl
      ? Array.from(paginationEl.querySelectorAll('li a, li button, a, button, li'))
      : Array.from(doc.querySelectorAll('a, button, li')).filter((el) => {
          const t = cleanText(el.textContent);
          return /^(?:←\s*Previous|Previous|Next\s*→?|Next|\d+)$/i.test(t);
        });

    for (const el of candidates) {
      const txt = cleanText(el.textContent);
      if (/^\d+$/.test(txt)) {
        const num = parseInt(txt, 10);
        if (num > 0 && num <= 500 && num > maxPageNumber) {
          maxPageNumber = num;
        }
      }
    }

    const nextCandidates = paginationEl
      ? Array.from(paginationEl.querySelectorAll('li.a-last a, li.a-last, a, button'))
      : Array.from(doc.querySelectorAll('a, button, li'));

    for (const el of nextCandidates) {
      const txt = cleanText(el.textContent);
      if (!/^(?:Next\s*→?|Next\s+Page)$/i.test(txt) && !el.classList.contains('a-last')) continue;

      const disabled =
        el.disabled ||
        el.getAttribute('aria-disabled') === 'true' ||
        el.classList.contains('a-disabled') ||
        Boolean(el.closest('.a-disabled, [aria-disabled="true"]'));

      if (disabled) {
        isNextExplicitlyDisabled = true;
        continue;
      }

      const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href]');
      if (linkEl && linkEl.getAttribute('href')) {
        nextUrl = resolveUrl(linkEl.getAttribute('href'), currentUrl);
        hasEnabledNext = true;
        break;
      } else if (el.tagName === 'BUTTON' || el.tagName === 'A') {
        hasEnabledNext = true;
      }
    }

    if (!nextUrl && currentPageNum) {
      const targetLabel = String(currentPageNum + 1);
      for (const el of candidates) {
        if (cleanText(el.textContent) !== targetLabel) continue;
        const linkEl = el.tagName === 'A' ? el : el.querySelector('a[href]');
        if (linkEl && linkEl.getAttribute('href')) {
          nextUrl = resolveUrl(linkEl.getAttribute('href'), currentUrl);
          hasEnabledNext = true;
          break;
        }
      }
    }

    if (totalExpectedOrders && totalExpectedOrders > 0) {
      const estimatedPages = Math.ceil(totalExpectedOrders / 10);
      if (estimatedPages > maxPageNumber) {
        maxPageNumber = estimatedPages;
      }
    }

    return {
      nextUrl,
      maxPageNumber,
      hasEnabledNext,
      isNextExplicitlyDisabled
    };
  }

  function parseOrdersPage(doc, pageUrl, pageNumber) {
    const accountName = extractAccountName(doc);
    const { activeFilter, options: timeFilterOptions } = extractTimeFilters(doc, pageUrl);
    const totalExpectedOrders = extractTotalOrdersCount(doc);
    const cardEls = findOrderCardElements(doc);

    const orders = cardEls.map((cardEl) => parseOrderCard(cardEl, pageUrl, pageNumber));

    const isSignInPage = Boolean(
      doc.querySelector('#ap_email, #ap_password, form[name="signIn"]') ||
      /\/ap\/signin/i.test(pageUrl || '')
    );

    return {
      isSignInPage,
      accountName,
      activeFilter,
      timeFilterOptions,
      totalExpectedOrders,
      orders,
      pagination: extractPaginationInfo(doc, pageUrl, pageNumber, totalExpectedOrders)
    };
  }

  function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function getCategoryBadgeStyle(category) {
    const map = {
      'Christmas Presents': { bg: '#ffebee', text: '#b71c1c', border: '#ef9a9a' },
      'Grocery': { bg: '#e8f5e9', text: '#1b5e20', border: '#a5d6a7' },
      'Sports & Fitness': { bg: '#e8eaf6', text: '#1a237e', border: '#9fa8da' },
      'Beauty': { bg: '#fce4ec', text: '#880e4f', border: '#f48fb1' },
      'Clothing': { bg: '#ede7f6', text: '#4a148c', border: '#b39ddb' },
      'Computing': { bg: '#e3f2fd', text: '#0d47a1', border: '#90caf9' },
      'Electronics': { bg: '#e0f7fa', text: '#006064', border: '#80deea' },
      'Health & Wellness': { bg: '#e0f2f1', text: '#004d40', border: '#80cbc4' },
      'Home & Kitchen': { bg: '#fff3e0', text: '#e65100', border: '#ffcc80' },
      'Tools & Home Improvement': { bg: '#fbe9e7', text: '#bf360c', border: '#ffab91' },
      'Pet Supplies': { bg: '#f3e5f5', text: '#6a1b9a', border: '#ce93d8' },
      'Office & School': { bg: '#f1f8e9', text: '#33691e', border: '#c5e1a5' },
      'Books & Media': { bg: '#fff8e1', text: '#ff6f00', border: '#ffe082' },
      'Baby & Toys': { bg: '#fef3c7', text: '#92400e', border: '#fde68a' },
      'Automotive': { bg: '#eceff1', text: '#263238', border: '#b0bec5' },
      'UNCATEGORIZED': { bg: '#f3f4f6', text: '#374151', border: '#9ca3af' }
    };
    return map[category] || { bg: '#eef2ff', text: '#312e81', border: '#a5b4fc' };
  }

  function getBuyAgainIconSvg() {
    return `<svg class="buy-again-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#0f1111" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/>
      <path d="M3 3v5h5"/>
      <path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"/>
      <path d="M16 16h5v5"/>
      <rect x="9" y="9" width="6" height="6" rx="1" stroke-width="1.8"/>
    </svg>`;
  }

  function renderHighCategoryOptionsHtml(allHighCategories, selectedHighCat) {
    const sorted = sortAlpha([...(allHighCategories || []), selectedHighCat]);
    return [
      ...sorted.map(
        (c) => `<option value="${escapeHtml(c)}" ${c === selectedHighCat ? 'selected' : ''}>${escapeHtml(c)}</option>`
      ),
      `<option value="__CUSTOM__">+ Custom Category...</option>`
    ].join('');
  }

  function renderSubCategoryOptionsHtml(highCat, selectedFineCat, subCategoriesByHigh) {
    const existingList = (subCategoriesByHigh && subCategoriesByHigh[highCat]) || [];
    const sortedSubs = sortAlpha([
      ...existingList,
      selectedFineCat || (highCat === 'UNCATEGORIZED' ? 'Uncategorized' : `General ${highCat}`)
    ]);
    return [
      ...sortedSubs.map(
        (sub) =>
          `<option value="${escapeHtml(sub)}" ${sub === selectedFineCat ? 'selected' : ''}>${escapeHtml(sub)}</option>`
      ),
      `<option value="__CUSTOM_FINE__">+ Custom Sub-Category...</option>`
    ].join('');
  }

  function computeOrderItemCostShares(order) {
    const items =
      order.items && order.items.length > 0
        ? order.items
        : [
            {
              title: 'Amazon Order',
              highLevelCategory: order.highLevelCategory || 'UNCATEGORIZED',
              fineGrainCategory: order.fineGrainCategory || 'Uncategorized'
            }
          ];
    const orderTotal = typeof order.totalAmount === 'number' && !isNaN(order.totalAmount) ? order.totalAmount : 0;
    if (items.length === 1) {
      const singleParsed = parseCurrencyAmount(items[0].itemPrice).amount;
      return [orderTotal > 0 ? orderTotal : singleParsed];
    }

    const rawAmounts = items.map((it) => {
      const p = parseCurrencyAmount(it.itemPrice).amount;
      const qty = it.quantity && it.quantity > 1 ? it.quantity : 1;
      return p > 0 ? p * qty : 0;
    });
    const sumRaw = rawAmounts.reduce((a, b) => a + b, 0);

    if (sumRaw > 0 && orderTotal > 0) {
      return rawAmounts.map((val) => (val / sumRaw) * orderTotal);
    }
    if (sumRaw > 0) {
      return rawAmounts;
    }
    return items.map(() => orderTotal / items.length);
  }

  function renderOrderCardHtml(order, index, baseUrl, allHighCategories, subCategoriesByHigh) {
    const orderId = order.orderId || `order-${index + 1}`;
    const datePlaced = order.datePlaced || 'Unknown Date';
    const deliveryDate = order.deliveryDate || datePlaced;
    const total = order.total || '$0.00';
    const shipTo = order.shipTo || 'Account Holder';
    const shippingAddress = order.shippingAddress || '';
    const detailsUrl =
      order.orderDetailsUrl ||
      resolveUrl(`/gp/your-account/order-details?orderID=${encodeURIComponent(orderId)}`, baseUrl);
    const invoiceUrl =
      order.invoiceUrl ||
      resolveUrl(`/gp/css/summary/print.html?orderID=${encodeURIComponent(orderId)}`, baseUrl);

    const orderCategories = Array.from(
      new Set((order.items || []).map((i) => i.highLevelCategory || order.highLevelCategory || 'UNCATEGORIZED'))
    );
    const orderFineCategories = Array.from(
      new Set((order.items || []).map((i) => i.fineGrainCategory || order.fineGrainCategory || 'Uncategorized'))
    );
    const orderShares = computeOrderItemCostShares(order);
    let globalItemCounter = 0;

    const shipmentsHtml = (order.shipments || [])
      .map((shipment) => {
        const itemsHtml = (shipment.items || [])
          .map((item, itemIdx) => {
            const shareIdx = globalItemCounter++;
            const imgSrc = item.imageDataUrl || item.imageUrl || '';
            const highCat = item.highLevelCategory || 'UNCATEGORIZED';
            const fineCat = item.fineGrainCategory || 'Uncategorized';
            const catStyle = getCategoryBadgeStyle(highCat);
            const itemDeliv = item.deliveryDate || shipment.deliveryDate || deliveryDate;
            const itemCostNum =
              typeof item._computedCostShare === 'number'
                ? item._computedCostShare
                : orderShares[shareIdx] !== undefined
                  ? orderShares[shareIdx]
                  : parseCurrencyAmount(item.itemPrice || item.price || '').amount || order.totalAmount || 0;
            const itemCostDisplay =
              item.itemPrice ||
              (order.items && order.items.length === 1
                ? total
                : `${order.currency || '$'}${Number(itemCostNum).toFixed(2)} (est. of ${total})`);
            const itemKey = `order:${orderId}:${item.asin || normalizeTitleKey(item.title) || itemIdx}`;

            const selectHighOptionsHtml = renderHighCategoryOptionsHtml(allHighCategories, highCat);
            const selectFineOptionsHtml = renderSubCategoryOptionsHtml(highCat, fineCat, subCategoriesByHigh);

            const qtyBadge =
              item.quantity && item.quantity > 1
                ? `<span class="item-qty-badge" title="Quantity: ${item.quantity}">${item.quantity}</span>`
                : '';

            const metaLinesHtml = (item.metadataLines || [])
              .map((line) => `<div class="item-meta-line">${escapeHtml(line)}</div>`)
              .join('\n                  ');

            const itemBtns = item.itemButtons || [];
            const itemBtnsHtml = itemBtns
              .map((btn) => {
                const isBuyAgain = btn.icon === 'buy-again' || /buy\s+it\s+again/i.test(btn.label);
                const cls = isBuyAgain
                  ? 'amz-btn amz-btn-buy-again'
                  : btn.variant === 'primary'
                    ? 'amz-btn amz-btn-yellow'
                    : 'amz-btn amz-btn-white';
                const hrefAttr = btn.url
                  ? `href="${escapeHtml(btn.url)}" target="_blank" rel="noopener noreferrer"`
                  : '';
                return `<a ${hrefAttr} class="${cls}">
                  ${isBuyAgain ? getBuyAgainIconSvg() : ''}
                  <span>${escapeHtml(btn.label)}</span>
                </a>`;
              })
              .join('\n                    ');

            return `<div class="order-item-row"
              data-item-key="${escapeHtml(itemKey)}"
              data-asin="${escapeHtml(item.asin || '')}"
              data-title-key="${escapeHtml(normalizeTitleKey(item.title))}"
              data-item-cost-num="${escapeHtml(String(Number(itemCostNum).toFixed(2)))}"
              data-high-category="${escapeHtml(highCat)}"
              data-fine-category="${escapeHtml(fineCat)}">
              <div class="order-item-thumb-wrap">
                ${
                  imgSrc
                    ? `<a href="${escapeHtml(item.productUrl || detailsUrl)}" target="_blank" rel="noopener noreferrer" class="order-item-img-link">
                        <img src="${escapeHtml(imgSrc)}" alt="${escapeHtml(item.title)}" class="order-item-img" loading="lazy" />
                        ${qtyBadge}
                      </a>`
                    : `<div class="order-item-img-placeholder">No Image</div>`
                }
              </div>
              <div class="order-item-info">
                <div class="item-category-row">
                  <select class="cat-select-high js-high-cat-select"
                    data-item-key="${escapeHtml(itemKey)}"
                    data-order-id="${escapeHtml(orderId)}"
                    data-asin="${escapeHtml(item.asin || '')}"
                    style="background:${catStyle.bg};color:${catStyle.text};border-color:${catStyle.border};"
                    title="Select or add a High-Level Category (sorted alphabetically)">
                    ${selectHighOptionsHtml}
                  </select>
                  <span class="cat-badge-sep">›</span>
                  <select class="cat-select-fine js-fine-cat-select"
                    data-item-key="${escapeHtml(itemKey)}"
                    data-order-id="${escapeHtml(orderId)}"
                    data-asin="${escapeHtml(item.asin || '')}"
                    title="Select or add a Sub-Category (remembered for this High-Level Category)">
                    ${selectFineOptionsHtml}
                  </select>
                </div>
                <a href="${escapeHtml(item.productUrl || detailsUrl)}" target="_blank" rel="noopener noreferrer" class="order-item-title">${escapeHtml(item.title)}</a>
                <div class="item-key-facts">
                  <span class="item-fact-pill"><strong>Placed:</strong> ${escapeHtml(datePlaced)}</span>
                  <span class="item-fact-pill"><strong>Delivered:</strong> ${escapeHtml(itemDeliv)}</span>
                  <span class="item-fact-pill"><strong>Order #:</strong> ${escapeHtml(orderId)}</span>
                  <span class="item-fact-pill item-cost-pill"><strong>Cost:</strong> ${escapeHtml(itemCostDisplay)}</span>
                  <a href="#item-json" class="item-json-link js-view-item-json" data-item-key="${escapeHtml(itemKey)}" title="View formatted JSON of all extracted details and keywords for this item">{ } Item JSON &amp; Keywords</a>
                </div>
                <div class="order-item-meta">
                  ${metaLinesHtml}
                </div>
                ${itemBtnsHtml ? `<div class="order-item-actions">${itemBtnsHtml}</div>` : ''}
              </div>
            </div>`;
          })
          .join('\n              ');

        const rightActionsHtml = (shipment.actions || [])
          .map((act) => {
            const btnClass = act.variant === 'primary' ? 'amz-btn amz-btn-yellow' : 'amz-btn amz-btn-white';
            const hrefAttr = act.url
              ? `href="${escapeHtml(act.url)}" target="_blank" rel="noopener noreferrer"`
              : '';
            return `<a ${hrefAttr} class="${btnClass}">${escapeHtml(act.label)}</a>`;
          })
          .join('\n                ');

        return `<div class="order-delivery-box">
          <div class="order-delivery-grid">
            <div class="order-items-col">
              ${itemsHtml}
            </div>
            ${
              rightActionsHtml
                ? `<div class="order-right-actions-col">
                    ${rightActionsHtml}
                  </div>`
                : ''
            }
          </div>
        </div>`;
      })
      .join('\n        ');

    return `<article class="order-card js-order-card"
      id="order-${escapeHtml(orderId)}"
      data-order-id="${escapeHtml(orderId)}"
      data-order-date="${escapeHtml(datePlaced)}"
      data-order-iso-date="${escapeHtml(order.isoDate || '')}"
      data-delivery-date="${escapeHtml(deliveryDate)}"
      data-delivery-iso-date="${escapeHtml(order.deliveryDateIso || order.isoDate || '')}"
      data-order-total="${escapeHtml(total)}"
      data-order-total-num="${escapeHtml(String(order.totalAmount || 0))}"
      data-high-categories="${escapeHtml(orderCategories.join('|'))}"
      data-fine-categories="${escapeHtml(orderFineCategories.join('|'))}"
      data-high-category="${escapeHtml(order.highLevelCategory || 'UNCATEGORIZED')}"
      data-fine-category="${escapeHtml(order.fineGrainCategory || 'Uncategorized')}"
      data-ship-to="${escapeHtml(shipTo)}"
      data-page="${escapeHtml(String(order.pageNumber || 1))}">
      <header class="order-card-header">
        <div class="order-header-left">
          <div class="order-header-field">
            <div class="order-header-label">ORDER PLACED</div>
            <div class="order-header-value">${escapeHtml(datePlaced)}</div>
          </div>
          <div class="order-header-field">
            <div class="order-header-label">DELIVERY DATE</div>
            <div class="order-header-value order-delivery-val">${escapeHtml(deliveryDate)}</div>
          </div>
          <div class="order-header-field">
            <div class="order-header-label">COST (TOTAL)</div>
            <div class="order-header-value order-cost-val">${escapeHtml(total)}</div>
          </div>
          <div class="order-header-field">
            <div class="order-header-label">SHIP TO</div>
            <div class="order-header-value">
              <span class="order-ship-to-link" ${shippingAddress ? `title="${escapeHtml(shippingAddress)}"` : ''}>
                ${escapeHtml(shipTo)} ▾
              </span>
            </div>
          </div>
        </div>
        <div class="order-header-right">
          <div class="order-id-line">ORDER # <strong>${escapeHtml(orderId)}</strong></div>
          <div class="order-header-links">
            <a href="${escapeHtml(detailsUrl)}" target="_blank" rel="noopener noreferrer">View order details</a>
            <span class="order-link-sep">|</span>
            <a href="${escapeHtml(invoiceUrl)}" target="_blank" rel="noopener noreferrer">View invoice</a>
          </div>
        </div>
      </header>
      ${shipmentsHtml}
    </article>`;
  }

  /**
   * Renders the HTML rows for the Category & Sub-Category Totals table (#category-totals-tbody).
   */
  function renderCategoryTotalsTableRowsHtml(categoryBreakdown, totalSpent, currency) {
    const safeTotalSpent = totalSpent > 0 ? totalSpent : 1;
    const rowsHtml = [];

    for (const catEntry of categoryBreakdown) {
      const st = getCategoryBadgeStyle(catEntry.highLevelCategory);
      const catPctTotal = totalSpent > 0 ? ((catEntry.estimatedSpend / safeTotalSpent) * 100).toFixed(1) : '0.0';
      const subList = catEntry.subCategories || [];

      rowsHtml.push(`<tr class="totals-high-row" data-totals-high="${escapeHtml(catEntry.highLevelCategory)}">
        <td>
          <span class="totals-cat-badge" style="background:${st.bg};color:${st.text};border-color:${st.border};">
            ${escapeHtml(catEntry.highLevelCategory)}
          </span>
        </td>
        <td>
          <strong class="totals-all-subs-label">All Sub-Categories (${subList.length})</strong>
        </td>
        <td class="totals-num-col"><strong>${catEntry.orderCount}</strong></td>
        <td class="totals-num-col"><strong>${catEntry.itemCount}</strong></td>
        <td class="totals-cost-col"><strong>${escapeHtml(catEntry.estimatedSpendFormatted)}</strong></td>
        <td class="totals-num-col"><strong>100.0%</strong></td>
        <td class="totals-bar-col">
          <div class="totals-pct-wrap">
            <span class="totals-pct-text"><strong>${catPctTotal}%</strong></span>
            <div class="totals-pct-bar-bg">
              <div class="totals-pct-bar-fill" style="width:${Math.min(100, Number(catPctTotal))}%;background:${st.text};"></div>
            </div>
          </div>
        </td>
        <td class="totals-action-col">
          <button type="button" class="totals-filter-btn" data-filter-high="${escapeHtml(catEntry.highLevelCategory)}" data-filter-fine="">
            Filter Category →
          </button>
        </td>
      </tr>`);

      for (const sub of subList) {
        const subPctCat =
          catEntry.estimatedSpend > 0 ? ((sub.estimatedSpend / catEntry.estimatedSpend) * 100).toFixed(1) : '0.0';
        const subPctTotal = totalSpent > 0 ? ((sub.estimatedSpend / safeTotalSpent) * 100).toFixed(1) : '0.0';
        rowsHtml.push(`<tr class="totals-sub-row" data-totals-high="${escapeHtml(catEntry.highLevelCategory)}" data-totals-fine="${escapeHtml(sub.fineGrainCategory)}">
          <td class="totals-sub-indent">
            <span class="totals-sub-arrow">↳</span>
            <span class="totals-sub-parent-name">${escapeHtml(catEntry.highLevelCategory)}</span>
          </td>
          <td>
            <span class="totals-sub-badge">${escapeHtml(sub.fineGrainCategory)}</span>
          </td>
          <td class="totals-num-col">${sub.orderCount}</td>
          <td class="totals-num-col">${sub.itemCount}</td>
          <td class="totals-cost-col">${escapeHtml(sub.estimatedSpendFormatted)}</td>
          <td class="totals-num-col">${subPctCat}%</td>
          <td class="totals-bar-col">
            <div class="totals-pct-wrap">
              <span class="totals-pct-text">${subPctTotal}%</span>
              <div class="totals-pct-bar-bg">
                <div class="totals-pct-bar-fill" style="width:${Math.min(100, Number(subPctTotal))}%;background:#007185;"></div>
              </div>
            </div>
          </td>
          <td class="totals-action-col">
            <button type="button" class="totals-filter-btn totals-filter-sub-btn" data-filter-high="${escapeHtml(catEntry.highLevelCategory)}" data-filter-fine="${escapeHtml(sub.fineGrainCategory)}">
              Filter Sub-Cat →
            </button>
          </td>
        </tr>`);
      }
    }

    return rowsHtml.join('\n              ');
  }

  /**
   * Builds the complete single HTML report:
   * - Uses 100% full width of the resizable browser viewport (no right-side clipping or hidden scrollbars)
   * - Column 1: Order Placed & Delivery Date (sortable by either Order Placed Date or Delivery Date)
   * - Column 2: Order Number
   * - Column 3: Items with Image Thumbnails
   * - Column 4: Cost (sortable by Cost High -> Low or Low -> High!)
   * - Column 5: Alphabetically-sorted High-Level Category dropdown
   * - Column 6: Alphabetically-sorted Sub-Category (Finer-Grain Category) dropdown remembered per High-Level Category
   * - Search / Filter bar for Items AND Cost (supports cost expressions like >50, <25, 20-100, and Min/Max $)
   * - Report Tab of Totals for Categories and Sub-Categories (with live updates when categories change)
   */
  function generateSingleHtmlReport(reportData) {
    const {
      yearLabel = '2026',
      timeFilter = 'year-2026',
      accountName = 'Customer',
      originUrl = 'https://www.amazon.com',
      orders = [],
      pagesFetched = 1,
      totalExpectedOrders = null,
      customHighCategories = [],
      subCategoriesByHigh: passedSubCategoriesByHigh = {},
      generatedAt = new Date().toISOString()
    } = reportData;

    // Ensure holiday gift heuristic has been applied across the order set
    applyHolidayGiftCategorization(orders);

    const subCategoriesByHigh = buildSubCategoriesByHigh(passedSubCategoriesByHigh, orders);

    // Sort all High-Level Categories alphabetically!
    const allHighCategories = sortAlpha([
      ...DEFAULT_HIGH_CATEGORIES,
      ...(customHighCategories || []),
      ...Object.keys(subCategoriesByHigh),
      ...orders.flatMap((o) => (o.items || []).map((i) => i.highLevelCategory).filter(Boolean))
    ]);

    for (const h of allHighCategories) {
      if (!subCategoriesByHigh[h] || subCategoriesByHigh[h].length === 0) {
        subCategoriesByHigh[h] = [h === 'UNCATEGORIZED' ? 'Uncategorized' : `General ${h}`];
      }
    }

    const totalOrders = orders.length;
    const totalSpent = orders.reduce(
      (acc, o) => acc + (typeof o.totalAmount === 'number' ? o.totalAmount : 0),
      0
    );
    const currency = (orders.find((o) => o.currency) || {}).currency || '$';
    const totalItems = orders.reduce((acc, o) => acc + ((o.items && o.items.length) || 0), 0);

    const categoryStatsMap = new Map();
    for (const o of orders) {
      const items =
        o.items && o.items.length > 0
          ? o.items
          : [
              {
                highLevelCategory: o.highLevelCategory || 'UNCATEGORIZED',
                fineGrainCategory: o.fineGrainCategory || 'Uncategorized'
              }
            ];
      const shares = computeOrderItemCostShares(o);
      items.forEach((it, idx) => {
        const cat = it.highLevelCategory || 'UNCATEGORIZED';
        const fine =
          it.fineGrainCategory || (cat === 'UNCATEGORIZED' ? 'Uncategorized' : `General ${cat}`);
        const share = shares[idx] !== undefined ? shares[idx] : (o.totalAmount || 0) / items.length;
        it._computedCostShare = Number(share.toFixed(2));
        const cur = categoryStatsMap.get(cat) || {
          category: cat,
          itemCount: 0,
          orderIds: new Set(),
          spend: 0,
          subMap: new Map()
        };
        cur.itemCount += 1;
        cur.orderIds.add(o.orderId);
        cur.spend += share;

        const subCur = cur.subMap.get(fine) || {
          fineGrainCategory: fine,
          highLevelCategory: cat,
          itemCount: 0,
          orderIds: new Set(),
          spend: 0
        };
        subCur.itemCount += 1;
        subCur.orderIds.add(o.orderId);
        subCur.spend += share;
        cur.subMap.set(fine, subCur);

        categoryStatsMap.set(cat, cur);
      });
    }

    const categoryBreakdown = Array.from(categoryStatsMap.values())
      .map((c) => {
        const subCategories = Array.from(c.subMap.values())
          .map((s) => ({
            highLevelCategory: c.category,
            fineGrainCategory: s.fineGrainCategory,
            orderCount: s.orderIds.size,
            itemCount: s.itemCount,
            estimatedSpend: Number(s.spend.toFixed(2)),
            estimatedSpendFormatted: `${currency}${s.spend.toFixed(2)}`
          }))
          .sort(
            (a, b) =>
              b.estimatedSpend - a.estimatedSpend ||
              a.fineGrainCategory.localeCompare(b.fineGrainCategory, undefined, { sensitivity: 'base' })
          );
        return {
          highLevelCategory: c.category,
          orderCount: c.orderIds.size,
          itemCount: c.itemCount,
          estimatedSpend: Number(c.spend.toFixed(2)),
          estimatedSpendFormatted: `${currency}${c.spend.toFixed(2)}`,
          subCategories
        };
      })
      .sort((a, b) => b.estimatedSpend - a.estimatedSpend);

    const subCategoryBreakdown = categoryBreakdown.flatMap((c) => c.subCategories || []);

    const agentStructuredPayload = {
      schemaVersion: '1.5',
      reportSummary: {
        year: yearLabel,
        timeFilter,
        accountName,
        totalOrders,
        totalExpectedOrders: totalExpectedOrders || totalOrders,
        totalItems,
        totalSpent: Number(totalSpent.toFixed(2)),
        totalSpentFormatted: `${currency}${totalSpent.toFixed(2)}`,
        currency,
        pagesCombined: pagesFetched,
        generatedAt,
        sourceOrigin: originUrl,
        availableHighLevelCategories: allHighCategories,
        subCategoriesByHigh,
        categoryBreakdown,
        subCategoryBreakdown
      },
      orders: orders.map((o, idx) => {
        const shares = computeOrderItemCostShares(o);
        const isHolidayOrder = isLateNovEarlyDecDate(o.datePlaced || o.isoDate);
        return {
          index: idx + 1,
          orderNumber: o.orderId,
          orderId: o.orderId,
          orderPlacedDate: o.datePlaced,
          orderPlacedIso: o.isoDate,
          deliveryDate: o.deliveryDate,
          deliveryDateIso: o.deliveryDateIso || o.isoDate,
          cost: o.total,
          costAmount: o.totalAmount,
          currency: o.currency,
          highLevelCategory: o.highLevelCategory || 'UNCATEGORIZED',
          fineGrainCategory: o.fineGrainCategory || 'Uncategorized',
          shipTo: o.shipTo,
          orderDetailsUrl: o.orderDetailsUrl,
          invoiceUrl: o.invoiceUrl,
          sourcePage: o.pageNumber,
          items: (o.items || []).map((it, itemIdx) => {
            const itemKey = `order:${o.orderId}:${it.asin || normalizeTitleKey(it.title) || itemIdx}`;
            const itemCostNum = Number(
              (shares[itemIdx] !== undefined ? shares[itemIdx] : o.totalAmount || 0).toFixed(2)
            );
            const kwDiag = categorizeProduct(it.title, it.metadataLines || []);
            const currentHigh = it.highLevelCategory || 'UNCATEGORIZED';
            const currentFine = it.fineGrainCategory || 'Uncategorized';
            let resolvedSource = it.classificationSource || kwDiag.classificationSource;
            if (it.isUserCustomized) {
              resolvedSource = `User Customized Override (Base: ${kwDiag.classificationSource})`;
            } else if (currentHigh === 'Christmas Presents' && isHolidayOrder) {
              resolvedSource = `Holiday Gift Window (Late Nov / Early Dec) • Base: ${kwDiag.classificationSource}`;
            }

            return {
              itemKey,
              title: it.title,
              normalizedTitleKey: normalizeTitleKey(it.title),
              asin: it.asin || null,
              quantity: it.quantity || 1,
              cost: it.itemPrice || (o.items && o.items.length === 1 ? o.total : `${currency}${itemCostNum.toFixed(2)}`),
              costAmount: itemCostNum,
              itemPriceRaw: it.itemPrice || null,
              orderPlacedDate: o.datePlaced,
              orderPlacedIso: o.isoDate,
              deliveryDate: it.deliveryDate || o.deliveryDate,
              deliveryDateIso: it.deliveryDateIso || o.deliveryDateIso || o.isoDate,
              deliveryStatus: it.deliveryStatus || o.deliveryStatus || 'Delivered',
              highLevelCategory: currentHigh,
              fineGrainCategory: currentFine,
              baseHighCategory: it.baseHighCategory || kwDiag.highLevelCategory || 'UNCATEGORIZED',
              baseFineCategory: it.baseFineCategory || kwDiag.fineGrainCategory || 'Uncategorized',
              categoryConfidence: it.categoryConfidence || kwDiag.confidence || 'low',
              isUserCustomized: Boolean(it.isUserCustomized),
              imageUrl: it.imageUrl || null,
              productUrl: it.productUrl || null,
              returnWindow: it.returnWindow || null,
              autoDelivered: it.autoDelivered || null,
              metadataLines: it.metadataLines || [],
              itemButtons: (it.itemButtons || []).map((b) => ({
                label: b.label,
                url: b.url || null,
                variant: b.variant || 'secondary'
              })),
              orderContext: {
                orderNumber: o.orderId,
                orderPlacedDate: o.datePlaced,
                orderPlacedIso: o.isoDate,
                deliveryDate: o.deliveryDate,
                deliveryDateIso: o.deliveryDateIso || o.isoDate,
                orderTotal: o.total,
                orderTotalAmount: o.totalAmount,
                currency: o.currency || currency,
                shipTo: o.shipTo || 'Account Holder',
                shippingAddress: o.shippingAddress || null,
                isHolidayWindow: isHolidayOrder,
                orderItemCount: (o.items && o.items.length) || 1,
                sourcePage: o.pageNumber || 1,
                orderDetailsUrl: o.orderDetailsUrl,
                invoiceUrl: o.invoiceUrl
              },
              toolKeywordAnalysis: {
                inspectedText: kwDiag.inspectedText,
                titleKeywords: kwDiag.titleKeywords,
                metadataKeywords: kwDiag.metadataKeywords,
                allExtractedKeywords: kwDiag.allExtractedKeywords,
                matchedKeywords: kwDiag.matchedKeywords,
                classificationSource: resolvedSource,
                confidence: it.categoryConfidence || kwDiag.confidence || 'low',
                ruleScore: kwDiag.ruleScore,
                candidateCategoryMatches: kwDiag.candidateCategoryMatches
              }
            };
          })
        };
      })
    };

    const jsonString = JSON.stringify(agentStructuredPayload, null, 2).replace(/<\/script/gi, '<\\/script');

    const categoryChipsHtml = [
      `<button type="button" class="cat-filter-chip active" data-cat="ALL">
        All Categories <span>(${totalOrders} orders • ${escapeHtml(currency)}${totalSpent.toFixed(2)})</span>
      </button>`,
      ...categoryBreakdown.map((c) => {
        const st = getCategoryBadgeStyle(c.highLevelCategory);
        return `<button type="button" class="cat-filter-chip" data-cat="${escapeHtml(c.highLevelCategory)}" style="--chip-bg:${st.bg};--chip-text:${st.text};--chip-border:${st.border};">
          ${escapeHtml(c.highLevelCategory)} <span>(${c.itemCount} • ${escapeHtml(c.estimatedSpendFormatted)})</span>
        </button>`;
      })
    ].join('\n        ');

    const totalsTableRowsHtml = renderCategoryTotalsTableRowsHtml(categoryBreakdown, totalSpent, currency);

    // Render Table Rows with Column 1 = Order Placed & Delivery Date (sortable!)
    const tableRowsHtml = orders
      .map((o) => {
        const items =
          o.items && o.items.length > 0
            ? o.items
            : [
                {
                  title: 'Amazon Order',
                  highLevelCategory: o.highLevelCategory || 'UNCATEGORIZED',
                  fineGrainCategory: o.fineGrainCategory || 'Uncategorized'
                }
              ];
        const shares = computeOrderItemCostShares(o);
        return items
          .map((it, itemIdx) => {
            const imgSrc = it.imageDataUrl || it.imageUrl || '';
            const highCat = it.highLevelCategory || o.highLevelCategory || 'UNCATEGORIZED';
            const fineCat = it.fineGrainCategory || o.fineGrainCategory || 'Uncategorized';
            const catStyle = getCategoryBadgeStyle(highCat);
            const delivDate = it.deliveryDate || o.deliveryDate || o.datePlaced;
            const delivIso = it.deliveryDateIso || o.deliveryDateIso || o.isoDate || '';
            const placedIso = o.isoDate || '';
            const itemCostNum = shares[itemIdx] !== undefined ? shares[itemIdx] : o.totalAmount || 0;
            const costStr =
              it.itemPrice ||
              (items.length === 1 ? o.total || '$0.00' : `${currency}${itemCostNum.toFixed(2)}`);
            const itemKey = `order:${o.orderId}:${it.asin || normalizeTitleKey(it.title) || itemIdx}`;

            const selectHighOptionsHtml = renderHighCategoryOptionsHtml(allHighCategories, highCat);
            const selectFineOptionsHtml = renderSubCategoryOptionsHtml(highCat, fineCat, subCategoriesByHigh);

            return `<tr class="report-table-row"
              data-item-key="${escapeHtml(itemKey)}"
              data-order-id="${escapeHtml(o.orderId)}"
              data-asin="${escapeHtml(it.asin || '')}"
              data-title-key="${escapeHtml(normalizeTitleKey(it.title))}"
              data-order-iso-date="${escapeHtml(placedIso)}"
              data-delivery-iso-date="${escapeHtml(delivIso)}"
              data-cost-num="${escapeHtml(String(o.totalAmount || 0))}"
              data-item-cost-num="${escapeHtml(String(itemCostNum.toFixed(2)))}"
              data-high-category="${escapeHtml(highCat)}"
              data-fine-category="${escapeHtml(fineCat)}">
              <!-- COLUMN 1: ORDER PLACED & DELIVERY DATE -->
              <td class="date-col">
                <div class="date-line placed-line">
                  <span class="date-tag">Placed:</span>
                  <strong>${escapeHtml(o.datePlaced)}</strong>
                </div>
                <div class="date-line deliv-line">
                  <span class="date-tag">Delivered:</span>
                  <strong>${escapeHtml(delivDate)}</strong>
                </div>
                ${it.returnWindow ? `<div class="date-sub-note">${escapeHtml(it.returnWindow)}</div>` : ''}
                ${isLateNovEarlyDecDate(o.datePlaced) ? `<div class="holiday-window-pill">🎄 Late Nov / Early Dec</div>` : ''}
              </td>
              <!-- COLUMN 2: ORDER NUMBER -->
              <td class="order-col">
                <a href="${escapeHtml(o.orderDetailsUrl)}" target="_blank" rel="noopener noreferrer" class="order-num-link">
                  <code>${escapeHtml(o.orderId)}</code>
                </a>
                <div class="table-sub-links">
                  <a href="${escapeHtml(o.orderDetailsUrl)}" target="_blank" rel="noopener noreferrer">Details</a> •
                  <a href="${escapeHtml(o.invoiceUrl)}" target="_blank" rel="noopener noreferrer">Invoice</a>
                </div>
              </td>
              <!-- COLUMN 3: ITEMS WITH IMAGE THUMBNAILS -->
              <td class="item-col">
                <div class="table-item-cell">
                  ${
                    imgSrc
                      ? `<a href="${escapeHtml(it.productUrl || o.orderDetailsUrl)}" target="_blank" rel="noopener noreferrer" class="table-thumb-link">
                          <img src="${escapeHtml(imgSrc)}" alt="${escapeHtml(it.title)}" class="table-thumb-img" loading="lazy" />
                        </a>`
                      : `<div class="table-thumb-placeholder">No Img</div>`
                  }
                  <div class="table-item-text">
                    <a href="${escapeHtml(it.productUrl || o.orderDetailsUrl)}" target="_blank" rel="noopener noreferrer" class="table-item-title">${escapeHtml(it.title)}</a>
                    <div class="table-item-sub">
                      ${it.asin ? `<span>ASIN: <code>${escapeHtml(it.asin)}</code></span>` : ''}
                      ${it.quantity && it.quantity > 1 ? `<span>Qty: <strong>${it.quantity}</strong></span>` : ''}
                      ${it.autoDelivered ? `<span class="sub-tag">${escapeHtml(it.autoDelivered)}</span>` : ''}
                      <a href="#item-json" class="item-json-link js-view-item-json" data-item-key="${escapeHtml(itemKey)}" title="View formatted JSON of all extracted details and keywords for this item">{ } Item JSON &amp; Keywords</a>
                    </div>
                  </div>
                </div>
              </td>
              <!-- COLUMN 4: COST -->
              <td class="cost-cell">
                <strong>${escapeHtml(costStr)}</strong>
                ${items.length > 1 && o.total ? `<div class="cost-sub-note">Order: ${escapeHtml(o.total)}</div>` : ''}
              </td>
              <!-- COLUMN 5: ALPHABETICALLY SORTED HIGH-LEVEL CATEGORY DROPDOWN -->
              <td class="cat-high-col">
                <select class="cat-select-high js-high-cat-select"
                  data-item-key="${escapeHtml(itemKey)}"
                  data-order-id="${escapeHtml(o.orderId)}"
                  data-asin="${escapeHtml(it.asin || '')}"
                  style="background:${catStyle.bg};color:${catStyle.text};border-color:${catStyle.border};"
                  title="Select or add a High-Level Category (sorted alphabetically)">
                  ${selectHighOptionsHtml}
                </select>
              </td>
              <!-- COLUMN 6: ALPHABETICALLY SORTED SUB-CATEGORY DROPDOWN (REMEMBERED PER CATEGORY) -->
              <td class="cat-fine-col">
                <select class="cat-select-fine js-fine-cat-select"
                  data-item-key="${escapeHtml(itemKey)}"
                  data-order-id="${escapeHtml(o.orderId)}"
                  data-asin="${escapeHtml(it.asin || '')}"
                  title="Select or add a Sub-Category (remembered as part of this High-Level Category)">
                  ${selectFineOptionsHtml}
                </select>
              </td>
            </tr>`;
          })
          .join('\n            ');
      })
      .join('\n            ');

    const cardsHtml = orders
      .map((o, idx) => renderOrderCardHtml(o, idx, originUrl, allHighCategories, subCategoriesByHigh))
      .join('\n\n      ');

    return `<!DOCTYPE html>
<!--
================================================================================
AMAZON ORDERS FULL-YEAR REPORT (${escapeHtml(yearLabel)}) — HUMAN & AI AGENT INGESTIBLE
================================================================================
AI AGENT INGESTION INSTRUCTIONS:
1. Structured JSON Index: The JSON script block (id="amazon-orders-data")
   immediately below contains the complete, normalized JSON dataset of all
   ${totalOrders} orders (${totalItems} items, total spend ${currency}${totalSpent.toFixed(2)}) for ${escapeHtml(yearLabel)},
   including both High-Level Category and Sub-Category breakdowns.
2. Columns & Sorting:
   - Column 1: Order Placed Date & Delivery Date (sortable by either date)
   - Column 2: Order Number
   - Column 3: Item & Image Thumbnail
   - Column 4: Cost (sortable by Cost High -> Low or Low -> High)
   - Column 5: High-Level Category Dropdown (sorted alphabetically)
   - Column 6: Sub-Category / Finer-Grain Category Dropdown (sorted alphabetically & remembered per High-Level Category)
================================================================================
-->
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Your Orders (${escapeHtml(yearLabel)}) - ${totalOrders} Orders - Amazon Report</title>
  <script type="application/json" id="amazon-orders-data">
${jsonString}
  </script>
  <style>
    * { box-sizing: border-box; }
    html, body {
      width: 100%;
      max-width: 100%;
      margin: 0;
      padding: 0;
      font-family: "Amazon Ember", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Arial, sans-serif;
      color: #0f1111;
      background-color: #ffffff;
      -webkit-font-smoothing: antialiased;
    }
    a { color: #007185; text-decoration: none; }
    a:hover { color: #c45500; text-decoration: underline; }
    .amz-nav-main {
      width: 100%;
      background-color: #131921;
      color: #ffffff;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 24px;
      gap: 16px;
      font-size: 14px;
    }
    .amz-logo-area { display: flex; align-items: baseline; gap: 2px; font-weight: 700; font-size: 22px; color: #fff; text-decoration: none; letter-spacing: -0.5px; }
    .amz-logo-area:hover { color: #fff; text-decoration: none; }
    .amz-logo-prime { color: #00a8e1; font-size: 13px; font-weight: 700; margin-left: 2px; }
    .amz-nav-right { display: flex; align-items: center; gap: 20px; font-size: 12px; white-space: nowrap; }
    .amz-nav-item-top { color: #ccc; font-size: 12px; line-height: 1.1; }
    .amz-nav-item-bottom { color: #fff; font-weight: 700; font-size: 14px; line-height: 1.2; }
    /* Full-width resizable container */
    .yo-container {
      width: 100%;
      max-width: 100%;
      margin: 0;
      padding: 16px 24px 60px;
    }
    .yo-breadcrumbs { font-size: 13px; margin-bottom: 10px; color: #565959; }
    .yo-breadcrumbs .yo-crumb-current { color: #c45500; }
    .yo-title-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 12px;
      margin-bottom: 14px;
    }
    .yo-title { font-size: 26px; font-weight: 400; margin: 0; color: #0f1111; }
    /* Item & Cost Search / Filter Controls */
    .yo-search-bar {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
      background: #f8fafa;
      border: 1px solid #d5d9d9;
      border-radius: 8px;
      padding: 6px 10px;
    }
    .yo-search-input-wrap { position: relative; display: flex; align-items: center; }
    .yo-search-icon { position: absolute; left: 10px; color: #565959; pointer-events: none; font-size: 13px; }
    .yo-search-input {
      width: 260px;
      max-width: 100%;
      height: 32px;
      padding: 3px 10px 3px 30px;
      border: 1px solid #888c8c;
      border-radius: 4px;
      font-size: 13px;
      color: #0f1111;
      background: #fff;
      outline: none;
    }
    .yo-cost-filter-input {
      width: 195px;
      max-width: 100%;
      height: 32px;
      padding: 3px 10px 3px 26px;
      border: 1px solid #888c8c;
      border-radius: 4px;
      font-size: 13px;
      color: #0f1111;
      background: #fff;
      outline: none;
    }
    .yo-cost-bound-input {
      width: 82px;
      height: 32px;
      padding: 3px 8px;
      border: 1px solid #888c8c;
      border-radius: 4px;
      font-size: 13px;
      color: #0f1111;
      background: #fff;
      outline: none;
    }
    .yo-search-input:focus, .yo-cost-filter-input:focus, .yo-cost-bound-input:focus {
      border-color: #007185;
      box-shadow: 0 0 0 3px #c8f3fa;
    }
    .yo-search-btn {
      background-color: #303333;
      color: #ffffff;
      border: 1px solid #303333;
      border-radius: 99px;
      height: 32px;
      padding: 0 14px;
      font-size: 13px;
      font-weight: 700;
      cursor: pointer;
    }
    .yo-clear-btn {
      background-color: #ffffff;
      color: #0f1111;
      border: 1px solid #d5d9d9;
      border-radius: 99px;
      height: 32px;
      padding: 0 12px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }
    .yo-clear-btn:hover { background-color: #f0f2f2; }
    .active-subcat-pill {
      display: none;
      align-items: center;
      gap: 6px;
      background: #edfdff;
      border: 1px solid #007185;
      color: #007185;
      border-radius: 99px;
      padding: 3px 10px;
      font-size: 12px;
      font-weight: 700;
    }
    .active-subcat-pill button {
      border: none;
      background: transparent;
      color: #007185;
      cursor: pointer;
      font-weight: 700;
      padding: 0 2px;
    }
    /* Category Filter & Live Cost Breakdown Strip */
    .category-filter-bar {
      width: 100%;
      background: #f8fafa;
      border: 1px solid #d5d9d9;
      border-radius: 8px;
      padding: 10px 14px;
      margin-bottom: 12px;
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px;
    }
    .cat-filter-label {
      font-size: 12px;
      font-weight: 700;
      color: #565959;
      text-transform: uppercase;
      margin-right: 4px;
    }
    .cat-filter-chip {
      border: 1px solid var(--chip-border, #d5d9d9);
      background: var(--chip-bg, #ffffff);
      color: var(--chip-text, #0f1111);
      border-radius: 99px;
      padding: 4px 11px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.15s ease;
    }
    .cat-filter-chip span { font-weight: 400; opacity: 0.88; margin-left: 2px; }
    .cat-filter-chip.active {
      outline: 2px solid #007185;
      box-shadow: 0 1px 4px rgba(0, 113, 133, 0.25);
    }
    /* Sort & Customization Toolbar */
    .sort-custom-bar {
      width: 100%;
      background: #ffffff;
      border: 1px solid #d5d9d9;
      border-radius: 8px;
      padding: 9px 14px;
      margin-bottom: 14px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 10px;
      font-size: 13px;
    }
    .sort-controls-group {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 8px;
    }
    .sort-btn {
      border: 1px solid #d5d9d9;
      background: #f0f2f2;
      color: #0f1111;
      border-radius: 6px;
      padding: 5px 11px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }
    .sort-btn.active {
      background: #edfdff;
      border-color: #007185;
      color: #007185;
    }
    /* Tabs & Toolbar */
    .yo-tabs {
      display: flex;
      align-items: center;
      gap: 24px;
      border-bottom: 1px solid #d5d9d9;
      margin-bottom: 14px;
      padding-left: 8px;
      font-size: 14px;
      flex-wrap: wrap;
    }
    .yo-tab {
      padding: 8px 4px 10px;
      color: #007185;
      cursor: pointer;
      border-bottom: 2px solid transparent;
      margin-bottom: -1px;
      user-select: none;
    }
    .yo-tab.active {
      color: #0f1111;
      font-weight: 700;
      border-bottom-color: #e47911;
    }
    .yo-filter-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 10px;
      margin-bottom: 14px;
      font-size: 14px;
    }
    .yo-count-left { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .yo-year-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background-color: #f0f2f2;
      border: 1px solid #d5d9d9;
      border-radius: 8px;
      padding: 4px 10px;
      font-size: 13px;
      color: #0f1111;
      font-weight: 600;
    }
    .yo-filter-right { display: flex; align-items: center; gap: 8px; font-size: 13px; flex-wrap: wrap; }
    .yo-tool-pill {
      font-size: 12px;
      padding: 4px 11px;
      border-radius: 99px;
      border: 1px solid #d5d9d9;
      background: #f8fafa;
      color: #0f1111;
      cursor: pointer;
      font-weight: 600;
    }
    .yo-tool-pill:hover { background: #edfdff; border-color: #007185; }
    .yo-tool-pill-primary {
      background: #ffd814;
      border-color: #fcd200;
    }
    /* Customizable Category & Sub-Category Dropdown Controls */
    .item-category-row {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 6px;
      margin-bottom: 6px;
    }
    .cat-select-high {
      font-size: 12px;
      font-weight: 700;
      padding: 4px 8px;
      border-radius: 8px;
      border: 1px solid #d5d9d9;
      cursor: pointer;
      outline: none;
      max-width: 100%;
      text-overflow: ellipsis;
    }
    .cat-badge-sep { color: #888c8c; font-size: 12px; }
    .cat-select-fine {
      font-size: 12px;
      font-weight: 600;
      padding: 4px 8px;
      border-radius: 6px;
      background: #f0f2f2;
      color: #0f1111;
      border: 1px solid #d5d9d9;
      cursor: pointer;
      outline: none;
      max-width: 100%;
      text-overflow: ellipsis;
    }
    .cat-select-fine:hover, .cat-select-fine:focus {
      border-color: #007185;
      background: #edfdff;
    }
    /* Category & Sub-Category Totals Tab Section */
    #category-totals-section {
      width: 100%;
      max-width: 100%;
      margin-bottom: 28px;
      display: none;
    }
    .totals-header-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 12px;
      background: #f8fafa;
      border: 1px solid #d5d9d9;
      border-bottom: none;
      border-radius: 8px 8px 0 0;
      padding: 12px 16px;
    }
    .totals-header-title {
      font-size: 16px;
      font-weight: 700;
      color: #0f1111;
      margin: 0;
    }
    .totals-header-sub {
      font-size: 12px;
      color: #565959;
      margin-top: 2px;
    }
    .totals-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
      background: #fff;
    }
    .totals-table th, .totals-table td {
      border-bottom: 1px solid #eaeded;
      padding: 10px 12px;
      text-align: left;
      vertical-align: middle;
    }
    .totals-table th {
      background: #f0f2f2;
      font-size: 11px;
      font-weight: 700;
      text-transform: uppercase;
      color: #565959;
      letter-spacing: 0.3px;
    }
    .totals-high-row td {
      background-color: #fbfdfd;
      border-top: 1px solid #d5d9d9;
    }
    .totals-sub-row td {
      background-color: #ffffff;
      color: #333;
    }
    .totals-sub-row:hover td, .totals-high-row:hover td {
      background-color: #f2fafa;
    }
    .totals-cat-badge {
      display: inline-block;
      padding: 4px 10px;
      border-radius: 99px;
      border: 1px solid #d5d9d9;
      font-size: 12px;
      font-weight: 700;
    }
    .totals-all-subs-label {
      color: #0f1111;
      font-size: 12px;
    }
    .totals-sub-indent {
      padding-left: 28px !important;
      color: #565959;
      font-size: 12px;
    }
    .totals-sub-arrow {
      color: #888c8c;
      margin-right: 4px;
      font-weight: 700;
    }
    .totals-sub-badge {
      display: inline-block;
      background: #f0f2f2;
      border: 1px solid #d5d9d9;
      border-radius: 6px;
      padding: 2px 8px;
      font-size: 12px;
      font-weight: 600;
      color: #0f1111;
    }
    .totals-num-col { text-align: right !important; width: 9%; white-space: nowrap; }
    .totals-cost-col { text-align: right !important; width: 12%; white-space: nowrap; font-size: 14px; }
    .totals-bar-col { width: 18%; }
    .totals-action-col { text-align: right !important; width: 13%; white-space: nowrap; }
    .totals-pct-wrap {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .totals-pct-text {
      width: 44px;
      text-align: right;
      font-size: 12px;
      flex-shrink: 0;
    }
    .totals-pct-bar-bg {
      flex: 1;
      height: 8px;
      background: #eaeded;
      border-radius: 99px;
      overflow: hidden;
      min-width: 50px;
    }
    .totals-pct-bar-fill {
      height: 100%;
      border-radius: 99px;
    }
    .totals-filter-btn {
      font-size: 11px;
      font-weight: 600;
      padding: 4px 9px;
      border-radius: 6px;
      border: 1px solid #d5d9d9;
      background: #ffffff;
      color: #007185;
      cursor: pointer;
    }
    .totals-filter-btn:hover {
      background: #edfdff;
      border-color: #007185;
    }
    .totals-table tfoot td {
      background: #f0f2f2;
      font-weight: 700;
      border-top: 2px solid #888c8c;
      font-size: 13px;
    }
    /* Full-Width Responsive Table View */
    #orders-table-section {
      width: 100%;
      max-width: 100%;
      margin-bottom: 28px;
    }
    .table-top-scroll {
      width: 100%;
      overflow-x: auto;
      overflow-y: hidden;
      height: 12px;
      margin-bottom: 2px;
      display: none;
    }
    .table-top-scroll-inner {
      height: 1px;
    }
    .report-table-wrap {
      width: 100%;
      max-width: 100%;
      border: 1px solid #d5d9d9;
      border-radius: 8px;
      overflow-x: auto;
      background: #fff;
    }
    .report-table {
      width: 100%;
      table-layout: fixed;
      border-collapse: collapse;
      font-size: 13px;
    }
    .report-table th, .report-table td {
      border-bottom: 1px solid #eaeded;
      padding: 10px 12px;
      text-align: left;
      vertical-align: middle;
      overflow-wrap: break-word;
      word-wrap: break-word;
    }
    .report-table th {
      background: #f0f2f2;
      font-size: 11px;
      font-weight: 700;
      text-transform: uppercase;
      color: #565959;
      letter-spacing: 0.3px;
    }
    /* Responsive Full-Width Column Proportions (Sums to 100%) */
    .report-table th:nth-child(1), .report-table td.date-col { width: 15%; }
    .report-table th:nth-child(2), .report-table td.order-col { width: 13%; }
    .report-table th:nth-child(3), .report-table td.item-col { width: 33%; }
    .report-table th:nth-child(4), .report-table td.cost-cell { width: 9%; }
    .report-table th:nth-child(5), .report-table td.cat-high-col { width: 15%; }
    .report-table th:nth-child(6), .report-table td.cat-fine-col { width: 15%; }

    .report-table td.cat-high-col .cat-select-high,
    .report-table td.cat-fine-col .cat-select-fine {
      width: 100%;
      display: block;
    }

    .th-sort-wrap {
      display: flex;
      align-items: center;
      gap: 4px;
      flex-wrap: wrap;
    }
    .th-sort-pill {
      font-size: 11px;
      padding: 2px 6px;
      border-radius: 4px;
      border: 1px solid #c5c9c9;
      background: #fff;
      color: #0f1111;
      cursor: pointer;
      text-transform: none;
      font-weight: 600;
    }
    .th-sort-pill.active {
      background: #007185;
      color: #fff;
      border-color: #007185;
    }
    .report-table tr:last-child td { border-bottom: none; }
    .report-table tr:hover td { background-color: #fbfdfd; }
    .date-tag { color: #565959; display: inline-block; width: 62px; }
    .date-line { font-size: 12px; line-height: 1.45; color: #0f1111; }
    .deliv-line { color: #067d62; }
    .date-sub-note { font-size: 11px; color: #565959; margin-top: 2px; }
    .cost-sub-note { font-size: 11px; color: #565959; margin-top: 2px; }
    .holiday-window-pill {
      display: inline-block;
      margin-top: 3px;
      font-size: 11px;
      font-weight: 600;
      color: #b71c1c;
      background: #ffebee;
      border: 1px solid #ef9a9a;
      border-radius: 4px;
      padding: 1px 6px;
    }
    .table-item-cell {
      display: flex;
      align-items: center;
      gap: 10px;
      width: 100%;
      min-width: 0;
    }
    .table-thumb-link {
      width: 56px;
      height: 56px;
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      border: 1px solid #eaeded;
      border-radius: 6px;
      background: #fff;
      padding: 3px;
    }
    .table-thumb-img {
      width: 48px;
      height: 48px;
      object-fit: contain;
      display: block;
    }
    .table-thumb-placeholder {
      width: 56px;
      height: 56px;
      flex-shrink: 0;
      border: 1px dashed #d5d9d9;
      border-radius: 6px;
      font-size: 11px;
      color: #888;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .table-item-text {
      flex: 1;
      min-width: 0;
    }
    .table-item-title {
      font-size: 13px;
      font-weight: 600;
      line-height: 1.35;
      color: #007185;
      display: block;
      margin-bottom: 3px;
    }
    .table-item-sub {
      font-size: 11px;
      color: #565959;
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .sub-tag { color: #007185; font-weight: 600; }
    .table-sub-links { font-size: 11px; margin-top: 3px; color: #565959; }
    .cost-cell { font-size: 14px; color: #0f1111; }
    /* Full-Width Order Card Styles */
    #orders-cards-section {
      width: 100%;
      max-width: 100%;
    }
    .order-card {
      width: 100%;
      max-width: 100%;
      border: 1px solid #d5d9d9;
      border-radius: 8px;
      margin-bottom: 18px;
      background-color: #ffffff;
      overflow: hidden;
    }
    .order-card-header {
      background-color: #f0f2f2;
      border-bottom: 1px solid #d5d9d9;
      padding: 14px 18px;
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 16px;
      color: #565959;
    }
    .order-header-left { display: flex; align-items: flex-start; gap: 32px; flex-wrap: wrap; }
    .order-header-label { font-size: 11px; line-height: 15px; text-transform: uppercase; color: #565959; font-weight: 600; }
    .order-header-value { font-size: 14px; line-height: 20px; color: #0f1111; }
    .order-delivery-val { color: #067d62; font-weight: 600; }
    .order-cost-val { font-weight: 700; }
    .order-ship-to-link { color: #007185; cursor: pointer; }
    .order-header-right { text-align: right; margin-left: auto; }
    .order-id-line { font-size: 12px; line-height: 16px; color: #0f1111; }
    .order-header-links { font-size: 13px; line-height: 20px; margin-top: 2px; }
    .order-link-sep { color: #d5d9d9; margin: 0 6px; }
    .order-delivery-box { padding: 18px; border-bottom: 1px solid #eaeded; }
    .order-delivery-box:last-child { border-bottom: none; }
    .order-delivery-grid {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 20px;
    }
    .order-items-col { flex: 1 1 480px; min-width: 0; }
    .order-item-row { display: flex; align-items: flex-start; gap: 18px; margin-bottom: 18px; }
    .order-item-row:last-child { margin-bottom: 0; }
    .order-item-thumb-wrap { width: 90px; flex-shrink: 0; display: flex; justify-content: center; position: relative; }
    .order-item-img-link { display: block; position: relative; }
    .order-item-img { width: 90px; height: 90px; object-fit: contain; display: block; }
    .item-qty-badge {
      position: absolute;
      bottom: -4px;
      right: -4px;
      background: #fff;
      border: 1px solid #d5d9d9;
      border-radius: 99px;
      min-width: 22px;
      height: 22px;
      font-size: 12px;
      font-weight: 700;
      color: #0f1111;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 5px;
    }
    .order-item-info { flex: 1; min-width: 0; }
    .order-item-title { font-size: 14px; font-weight: 600; line-height: 20px; color: #007185; display: block; margin-bottom: 6px; }
    .item-key-facts { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 6px; }
    .item-fact-pill { font-size: 12px; background: #f7fafa; border: 1px solid #e3e6e6; border-radius: 6px; padding: 2px 8px; color: #0f1111; }
    .item-cost-pill { background: #fffdf2; border-color: #f5d77f; }
    .order-item-meta { font-size: 12px; line-height: 18px; color: #565959; }
    .item-meta-line { margin-bottom: 2px; }
    .order-item-actions { margin-top: 8px; display: flex; flex-wrap: wrap; gap: 8px; }
    .order-right-actions-col { width: 215px; flex-shrink: 0; display: flex; flex-direction: column; gap: 6px; }
    .amz-btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      height: 30px;
      padding: 0 14px;
      border-radius: 99px;
      font-size: 13px;
      color: #0f1111;
      text-decoration: none;
      cursor: pointer;
      white-space: nowrap;
      box-shadow: 0 2px 5px rgba(213, 217, 217, 0.5);
    }
    .amz-btn:hover { color: #0f1111; text-decoration: none; }
    .amz-btn-yellow { background-color: #ffd814; border: 1px solid #fcd200; }
    .amz-btn-yellow:hover { background-color: #f7ca00; }
    .amz-btn-white { background-color: #ffffff; border: 1px solid #888c8c; }
    .amz-btn-white:hover { background-color: #f7fafa; }
    .amz-btn-buy-again { background-color: #ffd814; border: 1px solid #fcd200; gap: 6px; padding: 0 13px 0 10px; }
    /* Item JSON & Keywords Link & Popup Modal */
    .item-json-link {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11px;
      font-weight: 700;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      color: #007185;
      background: #edfdff;
      border: 1px solid #007185;
      border-radius: 5px;
      padding: 1px 7px;
      cursor: pointer;
      text-decoration: none;
      transition: all 0.12s ease;
    }
    .item-json-link:hover {
      background: #007185;
      color: #ffffff;
      text-decoration: none;
    }
    .item-json-modal-backdrop {
      display: none;
      position: fixed;
      inset: 0;
      z-index: 99999;
      background: rgba(15, 17, 17, 0.62);
      backdrop-filter: blur(2px);
      align-items: center;
      justify-content: center;
      padding: 20px;
    }
    .item-json-modal-backdrop.open {
      display: flex;
    }
    .item-json-modal {
      width: 100%;
      max-width: 920px;
      max-height: 90vh;
      background: #ffffff;
      border: 1px solid #d5d9d9;
      border-radius: 10px;
      box-shadow: 0 16px 42px rgba(0, 0, 0, 0.28);
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .item-json-modal-header {
      background: #f0f2f2;
      border-bottom: 1px solid #d5d9d9;
      padding: 12px 18px;
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
    }
    .item-json-modal-title-wrap { flex: 1; min-width: 0; }
    .item-json-modal-title {
      font-size: 15px;
      font-weight: 700;
      color: #0f1111;
      margin: 0 0 6px;
      line-height: 1.35;
    }
    .item-json-modal-sub {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      color: #565959;
    }
    .item-json-close-btn {
      border: 1px solid #d5d9d9;
      background: #ffffff;
      color: #0f1111;
      border-radius: 6px;
      width: 30px;
      height: 30px;
      font-size: 15px;
      font-weight: 700;
      cursor: pointer;
      flex-shrink: 0;
    }
    .item-json-close-btn:hover { background: #e3e6e6; }
    .item-json-modal-body {
      padding: 14px 18px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .item-json-kw-panel {
      background: #f8fafa;
      border: 1px solid #d5d9d9;
      border-radius: 8px;
      padding: 10px 14px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      font-size: 12px;
    }
    .item-json-kw-row {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      flex-wrap: wrap;
    }
    .item-json-kw-label {
      font-weight: 700;
      color: #565959;
      text-transform: uppercase;
      font-size: 11px;
      min-width: 155px;
      padding-top: 2px;
    }
    .item-json-kw-list {
      display: flex;
      flex-wrap: wrap;
      gap: 5px;
      flex: 1;
    }
    .kw-pill {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 11px;
      padding: 2px 7px;
      border-radius: 4px;
      border: 1px solid #d5d9d9;
      background: #ffffff;
      color: #0f1111;
      cursor: pointer;
    }
    .kw-pill:hover {
      border-color: #007185;
      background: #edfdff;
      color: #007185;
    }
    .kw-pill-matched {
      background: #e8f5e9;
      border-color: #81c784;
      color: #1b5e20;
      font-weight: 700;
    }
    .kw-pill-none {
      color: #b71c1c;
      background: #ffebee;
      border-color: #ef9a9a;
      cursor: default;
    }
    .item-json-code-wrap {
      position: relative;
      border: 1px solid #1e293b;
      border-radius: 8px;
      background: #0f172a;
      overflow: hidden;
    }
    .item-json-code-toolbar {
      background: #1e293b;
      padding: 6px 12px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      color: #cbd5e1;
      font-size: 11px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    }
    .item-json-pre {
      margin: 0;
      padding: 14px;
      overflow-x: auto;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
      font-size: 12px;
      line-height: 1.5;
      color: #e2e8f0;
      max-height: 440px;
      overflow-y: auto;
    }
    .json-key { color: #7dd3fc; }
    .json-str { color: #86efac; }
    .json-num { color: #fcd34d; }
    .json-bool { color: #c4b5fd; }
    .json-null { color: #94a3b8; }
    .item-json-modal-footer {
      border-top: 1px solid #d5d9d9;
      background: #f8fafa;
      padding: 10px 18px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      flex-wrap: wrap;
    }
    @media (max-width: 900px) {
      .report-table { table-layout: auto; min-width: 860px; }
      .table-top-scroll { display: block; }
    }
    @media (max-width: 720px) {
      .yo-container { padding: 12px 12px 40px; }
      .order-delivery-grid { flex-direction: column; }
      .order-right-actions-col { width: 100%; }
    }
    @media print {
      .amz-nav-main, .yo-tool-pill, .sort-custom-bar, .table-top-scroll, .item-json-link, .item-json-modal-backdrop { display: none !important; }
      .order-card, .report-table-row { break-inside: avoid; }
    }
  </style>
</head>
<body>
  <nav class="amz-nav-main" aria-label="Amazon Header">
    <a href="${escapeHtml(originUrl)}" target="_blank" rel="noopener noreferrer" class="amz-logo-area">
      amazon<span class="amz-logo-prime">prime</span>
    </a>
    <div class="amz-nav-right">
      <div>
        <div class="amz-nav-item-top">Hello, ${escapeHtml(accountName || 'Customer')}</div>
        <div class="amz-nav-item-bottom">Account &amp; Lists</div>
      </div>
      <div>
        <div class="amz-nav-item-top">Full-Year Report</div>
        <div class="amz-nav-item-bottom">${escapeHtml(yearLabel)} (${totalOrders} Orders)</div>
      </div>
    </div>
  </nav>

  <main class="yo-container">
    <div class="yo-breadcrumbs">
      <a href="${escapeHtml(originUrl)}/gp/css/homepage.html" target="_blank" rel="noopener noreferrer">Your Account</a>
      <span> › </span>
      <span class="yo-crumb-current">Your Orders Report (${escapeHtml(yearLabel)})</span>
    </div>

    <div class="yo-title-row">
      <h1 class="yo-title">Your Orders — ${escapeHtml(yearLabel)}</h1>
      <!-- Search / Filter Field for Items and Cost -->
      <div class="yo-search-bar" role="search" aria-label="Search and filter items and cost">
        <div class="yo-search-input-wrap">
          <span class="yo-search-icon">🔍</span>
          <input type="search" id="order-search-input" class="yo-search-input" placeholder="Search items, order #, category, ASIN..." aria-label="Search items and orders" />
        </div>
        <div class="yo-search-input-wrap">
          <span class="yo-search-icon">💲</span>
          <input type="search" id="cost-filter-input" class="yo-cost-filter-input" placeholder="Cost (e.g. >50, <25, 20-100)" aria-label="Filter by cost expression" title="Enter a cost filter like >50, >=100, <25, 20-100, or 49.99" />
        </div>
        <input type="number" id="cost-min-input" class="yo-cost-bound-input" placeholder="Min $" step="any" min="0" aria-label="Minimum Cost" title="Minimum item/order cost" />
        <input type="number" id="cost-max-input" class="yo-cost-bound-input" placeholder="Max $" step="any" min="0" aria-label="Maximum Cost" title="Maximum item/order cost" />
        <span class="active-subcat-pill" id="active-subcat-pill">
          <span id="active-subcat-text"></span>
          <button type="button" id="clear-subcat-btn" title="Clear Sub-Category filter">✕</button>
        </span>
        <button type="button" id="order-search-btn" class="yo-search-btn">Filter</button>
        <button type="button" id="clear-filters-btn" class="yo-clear-btn" title="Reset item search, cost filters, and category selection">✕ Clear</button>
      </div>
    </div>

    <!-- High-Level Category Filter & Live Spend Breakdown -->
    <div class="category-filter-bar" id="category-filter-bar" role="region" aria-label="Category Filter">
      <span class="cat-filter-label">Product Category (Live Totals):</span>
      <div id="category-chips-container" style="display:flex;flex-wrap:wrap;gap:8px;align-items:center;">
        ${categoryChipsHtml}
      </div>
    </div>

    <!-- Sort (Order Placed, Delivery Date, and Cost) & Custom Category Bar -->
    <div class="sort-custom-bar">
      <div class="sort-controls-group">
        <strong style="font-size:12px;color:#565959;text-transform:uppercase;">Sort By:</strong>
        <button type="button" class="sort-btn active" data-sort-field="placed" data-sort-dir="desc">
          Order Placed (Newest ↓)
        </button>
        <button type="button" class="sort-btn" data-sort-field="placed" data-sort-dir="asc">
          Order Placed (Oldest ↑)
        </button>
        <button type="button" class="sort-btn" data-sort-field="delivery" data-sort-dir="desc">
          Delivery Date (Newest ↓)
        </button>
        <button type="button" class="sort-btn" data-sort-field="delivery" data-sort-dir="asc">
          Delivery Date (Oldest ↑)
        </button>
        <button type="button" class="sort-btn" data-sort-field="cost" data-sort-dir="desc">
          Cost (High → Low ↓)
        </button>
        <button type="button" class="sort-btn" data-sort-field="cost" data-sort-dir="asc">
          Cost (Low → High ↑)
        </button>
      </div>
      <div class="sort-controls-group">
        <button type="button" class="yo-tool-pill" id="add-custom-cat-btn">+ Add Custom Category</button>
        <button type="button" class="yo-tool-pill" id="add-custom-subcat-btn">+ Add Sub-Category</button>
        <button type="button" class="yo-tool-pill yo-tool-pill-primary" id="save-updated-html-btn" title="Downloads this HTML report with all your customized categories and sub-categories baked into the HTML and Agent JSON">
          💾 Save Updated HTML Report
        </button>
      </div>
    </div>

    <!-- View Mode Tabs (including Category & Sub-Category Totals Report Tab) -->
    <div class="yo-tabs" role="tablist">
      <div class="yo-tab active" data-view="both" role="tab">Complete Report (Table + Cards)</div>
      <div class="yo-tab" data-view="table" role="tab">Categorized Thumbnail Table (<span id="tab-items-count">${totalItems}</span> Items)</div>
      <div class="yo-tab" data-view="cards" role="tab">Order Cards View (<span id="tab-orders-count">${totalOrders}</span> Orders)</div>
      <div class="yo-tab" data-view="totals" role="tab" id="tab-category-totals">📊 Category &amp; Sub-Category Totals</div>
    </div>

    <div class="yo-filter-row">
      <div class="yo-count-left">
        <span><strong id="visible-order-count">${totalOrders} orders</strong> (<span id="visible-item-count">${totalItems} items</span>) in</span>
        <span class="yo-year-badge">${escapeHtml(yearLabel)}</span>
        <span style="color:#565959; font-size:13px; margin-left:6px;" id="visible-spend-summary">
          • Filtered Cost: <strong>${escapeHtml(currency)}${totalSpent.toFixed(2)}</strong> (Report Total: <strong>${escapeHtml(currency)}${totalSpent.toFixed(2)}</strong> across <strong>${pagesFetched} ${pagesFetched === 1 ? 'page' : 'pages'}</strong>)
        </span>
      </div>
      <div class="yo-filter-right">
        <button type="button" class="yo-tool-pill" id="copy-agent-json-btn">📋 Copy JSON for Agent</button>
        <button type="button" class="yo-tool-pill" id="copy-cache-sync-btn" title="Copy customized category & sub-category cache token to import into extension">🔄 Copy Category Cache</button>
        <button type="button" class="yo-tool-pill" id="download-csv-btn">⬇ Download CSV</button>
      </div>
    </div>

    <!-- Section 0: Category & Sub-Category Totals Report Tab -->
    <section id="category-totals-section" aria-label="Category and Sub-Category Totals Report">
      <div class="totals-header-bar">
        <div>
          <h2 class="totals-header-title">📊 Category &amp; Sub-Category Totals Breakdown</h2>
          <div class="totals-header-sub" id="totals-tab-summary">
            Live totals across ${categoryBreakdown.length} High-Level Categories and ${subCategoryBreakdown.length} Sub-Categories (${totalItems} items • ${escapeHtml(currency)}${totalSpent.toFixed(2)})
          </div>
        </div>
        <div class="sort-controls-group">
          <strong style="font-size:11px;color:#565959;text-transform:uppercase;">Sort Totals:</strong>
          <button type="button" class="sort-btn totals-sort-btn active" data-totals-sort="spend-desc">Spend (High → Low ↓)</button>
          <button type="button" class="sort-btn totals-sort-btn" data-totals-sort="spend-asc">Spend (Low → High ↑)</button>
          <button type="button" class="sort-btn totals-sort-btn" data-totals-sort="alpha-asc">Category Name (A–Z)</button>
        </div>
      </div>
      <div class="report-table-wrap" style="border-radius: 0 0 8px 8px;">
        <table class="totals-table" id="category-totals-table">
          <thead>
            <tr>
              <th>High-Level Category</th>
              <th>Sub-Category (Finer-Grain)</th>
              <th class="totals-num-col">Orders</th>
              <th class="totals-num-col">Items</th>
              <th class="totals-cost-col">Total Cost</th>
              <th class="totals-num-col">% of Category</th>
              <th class="totals-bar-col">% of Report Spend</th>
              <th class="totals-action-col">Quick Filter</th>
            </tr>
          </thead>
          <tbody id="category-totals-tbody">
            ${totalsTableRowsHtml}
          </tbody>
          <tfoot id="category-totals-tfoot">
            <tr>
              <td>Grand Total (${categoryBreakdown.length} Categories)</td>
              <td>${subCategoryBreakdown.length} Active Sub-Categories</td>
              <td class="totals-num-col">${totalOrders}</td>
              <td class="totals-num-col">${totalItems}</td>
              <td class="totals-cost-col">${escapeHtml(currency)}${totalSpent.toFixed(2)}</td>
              <td class="totals-num-col">100.0%</td>
              <td class="totals-bar-col">100.0% of Total Spend</td>
              <td class="totals-action-col"></td>
            </tr>
          </tfoot>
        </table>
      </div>
    </section>

    <!-- Section 1: Categorized Order & Item Table (First Column = Order Placed & Delivery Date) -->
    <section id="orders-table-section" aria-label="Categorized Orders Table">
      <div class="table-top-scroll" id="table-top-scroll">
        <div class="table-top-scroll-inner" id="table-top-scroll-inner"></div>
      </div>
      <div class="report-table-wrap" id="report-table-wrap">
        <table class="report-table" id="orders-main-table">
          <thead>
            <tr>
              <th>
                <div class="th-sort-wrap">
                  <span>1. Order Placed &amp; Delivery Date</span>
                  <button type="button" class="th-sort-pill active" id="th-sort-placed" title="Sort by Order Placed Date">Placed ⇅</button>
                  <button type="button" class="th-sort-pill" id="th-sort-delivery" title="Sort by Delivery Date">Delivered ⇅</button>
                </div>
              </th>
              <th>Order Number</th>
              <th>Item &amp; Thumbnail</th>
              <th>
                <div class="th-sort-wrap">
                  <span>Cost</span>
                  <button type="button" class="th-sort-pill" id="th-sort-cost" title="Sort by Cost (High/Low)">Cost ⇅</button>
                </div>
              </th>
              <th>High-Level Category (A–Z)</th>
              <th>Sub-Category / Finer-Grain (A–Z)</th>
            </tr>
          </thead>
          <tbody id="orders-table-tbody">
            ${tableRowsHtml}
          </tbody>
        </table>
      </div>
    </section>

    <!-- Section 2: Full Order Cards -->
    <section id="orders-cards-section" aria-label="Orders Cards Feed">
      ${cardsHtml}
    </section>
  </main>

  <!-- Item Formatted JSON & Keywords Inspector Modal Popup -->
  <div class="item-json-modal-backdrop" id="item-json-modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="item-json-modal-title">
    <div class="item-json-modal">
      <header class="item-json-modal-header">
        <div class="item-json-modal-title-wrap">
          <h2 class="item-json-modal-title" id="item-json-modal-title">Item Details &amp; Keywords JSON</h2>
          <div class="item-json-modal-sub" id="item-json-modal-sub"></div>
        </div>
        <button type="button" class="item-json-close-btn" id="close-item-json-modal-x" title="Close (Esc)" aria-label="Close modal">✕</button>
      </header>
      <div class="item-json-modal-body">
        <div class="item-json-kw-panel" id="item-json-kw-panel">
          <div class="item-json-kw-row">
            <span class="item-json-kw-label">Classification Source:</span>
            <div class="item-json-kw-list" id="item-json-source-box"></div>
          </div>
          <div class="item-json-kw-row">
            <span class="item-json-kw-label">Matched Rule Keywords:</span>
            <div class="item-json-kw-list" id="item-json-matched-kws"></div>
          </div>
          <div class="item-json-kw-row">
            <span class="item-json-kw-label">Available Item Keywords:</span>
            <div class="item-json-kw-list" id="item-json-extracted-kws" title="Click any keyword pill to filter the report by that keyword"></div>
          </div>
        </div>
        <div class="item-json-code-wrap">
          <div class="item-json-code-toolbar">
            <span id="item-json-key-label">Formatted Item JSON Payload (Schema v1.5)</span>
            <span>Click any keyword pill above to filter report • Esc to close</span>
          </div>
          <pre class="item-json-pre" id="item-json-modal-pre"><code id="item-json-modal-code"></code></pre>
        </div>
      </div>
      <footer class="item-json-modal-footer">
        <span style="font-size:12px;color:#565959;">Shows all extracted DOM details, order context, and keyword tokens available to the categorizer &amp; AI agent.</span>
        <div style="display:flex;gap:8px;align-items:center;">
          <button type="button" class="yo-tool-pill yo-tool-pill-primary" id="copy-item-json-btn">📋 Copy Formatted JSON</button>
          <button type="button" class="yo-tool-pill" id="close-item-json-modal-btn">Close</button>
        </div>
      </footer>
    </div>
  </div>

  <script>
    (function () {
      const LOCAL_CACHE_KEY = 'amzReportCustomCategories_v2';
      const REPORT_CURRENCY = ${JSON.stringify(currency)};
      const TOTAL_ORDERS_COUNT = ${Number(totalOrders)};
      const TOTAL_ITEMS_COUNT = ${Number(totalItems)};
      const TOTAL_SPENT_AMOUNT = ${Number(totalSpent.toFixed(2))};
      const PAGES_FETCHED_COUNT = ${Number(pagesFetched)};

      const searchInput = document.getElementById('order-search-input');
      const costFilterInput = document.getElementById('cost-filter-input');
      const costMinInput = document.getElementById('cost-min-input');
      const costMaxInput = document.getElementById('cost-max-input');
      const searchBtn = document.getElementById('order-search-btn');
      const clearFiltersBtn = document.getElementById('clear-filters-btn');
      const activeSubcatPill = document.getElementById('active-subcat-pill');
      const activeSubcatText = document.getElementById('active-subcat-text');
      const clearSubcatBtn = document.getElementById('clear-subcat-btn');

      const countEl = document.getElementById('visible-order-count');
      const itemCountEl = document.getElementById('visible-item-count');
      const spendSummaryEl = document.getElementById('visible-spend-summary');
      const tbody = document.getElementById('orders-table-tbody');
      const cardsSec = document.getElementById('orders-cards-section');
      const tableSec = document.getElementById('orders-table-section');
      const totalsSec = document.getElementById('category-totals-section');
      const totalsTbody = document.getElementById('category-totals-tbody');
      const totalsTfoot = document.getElementById('category-totals-tfoot');
      const totalsSummaryEl = document.getElementById('totals-tab-summary');
      const chipsContainer = document.getElementById('category-chips-container');
      const tabs = Array.from(document.querySelectorAll('.yo-tab'));
      const sortBtns = Array.from(document.querySelectorAll('.sort-btn:not(.totals-sort-btn)'));
      const totalsSortBtns = Array.from(document.querySelectorAll('.totals-sort-btn'));
      const thSortPlaced = document.getElementById('th-sort-placed');
      const thSortDelivery = document.getElementById('th-sort-delivery');
      const thSortCost = document.getElementById('th-sort-cost');
      const topScroll = document.getElementById('table-top-scroll');
      const topScrollInner = document.getElementById('table-top-scroll-inner');
      const tableWrap = document.getElementById('report-table-wrap');
      const mainTable = document.getElementById('orders-main-table');

      let activeCategory = 'ALL';
      let activeSubCategory = '';
      let currentSortField = 'placed';
      let currentSortDir = 'desc';
      let currentTotalsSort = 'spend-desc';

      const BADGE_STYLES = {
        'Christmas Presents': { bg: '#ffebee', text: '#b71c1c', border: '#ef9a9a' },
        'Grocery': { bg: '#e8f5e9', text: '#1b5e20', border: '#a5d6a7' },
        'Sports & Fitness': { bg: '#e8eaf6', text: '#1a237e', border: '#9fa8da' },
        'Beauty': { bg: '#fce4ec', text: '#880e4f', border: '#f48fb1' },
        'Clothing': { bg: '#ede7f6', text: '#4a148c', border: '#b39ddb' },
        'Computing': { bg: '#e3f2fd', text: '#0d47a1', border: '#90caf9' },
        'Electronics': { bg: '#e0f7fa', text: '#006064', border: '#80deea' },
        'Health & Wellness': { bg: '#e0f2f1', text: '#004d40', border: '#80cbc4' },
        'Home & Kitchen': { bg: '#fff3e0', text: '#e65100', border: '#ffcc80' },
        'Tools & Home Improvement': { bg: '#fbe9e7', text: '#bf360c', border: '#ffab91' },
        'Pet Supplies': { bg: '#f3e5f5', text: '#6a1b9a', border: '#ce93d8' },
        'Office & School': { bg: '#f1f8e9', text: '#33691e', border: '#c5e1a5' },
        'Books & Media': { bg: '#fff8e1', text: '#ff6f00', border: '#ffe082' },
        'Baby & Toys': { bg: '#fef3c7', text: '#92400e', border: '#fde68a' },
        'Automotive': { bg: '#eceff1', text: '#263238', border: '#b0bec5' },
        'UNCATEGORIZED': { bg: '#f3f4f6', text: '#374151', border: '#9ca3af' }
      };

      function sortAlphaClient(list) {
        return Array.from(new Set((list || []).map((s) => String(s || '').trim()).filter(Boolean))).sort((a, b) =>
          a.localeCompare(b, undefined, { sensitivity: 'base' })
        );
      }

      function escapeHtmlClient(str) {
        return String(str === null || str === undefined ? '' : str)
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#39;');
      }

      function getStyleForCat(cat) {
        return BADGE_STYLES[cat] || { bg: '#eef2ff', text: '#312e81', border: '#a5b4fc' };
      }

      function getPayloadData() {
        const el = document.getElementById('amazon-orders-data');
        return el ? JSON.parse(el.textContent) : null;
      }

      function savePayloadData(data) {
        const el = document.getElementById('amazon-orders-data');
        if (el && data) {
          el.textContent = '\\n' + JSON.stringify(data, null, 2) + '\\n  ';
        }
      }

      // Initialize subCategoriesByHigh dictionary from embedded payload
      const initialPayload = getPayloadData();
      const subCategoriesByHigh =
        (initialPayload &&
          initialPayload.reportSummary &&
          initialPayload.reportSummary.subCategoriesByHigh) ||
        {};

      function loadLocalOverrides() {
        try {
          const raw = localStorage.getItem(LOCAL_CACHE_KEY);
          const parsed = raw ? JSON.parse(raw) : {};
          return {
            itemCategories: parsed.itemCategories || {},
            customHighCategories: parsed.customHighCategories || [],
            subCategoriesByHigh: parsed.subCategoriesByHigh || {}
          };
        } catch (_) {
          return { itemCategories: {}, customHighCategories: [], subCategoriesByHigh: {} };
        }
      }

      function persistOverridesToStorage(itemKey, asin, titleKey, highCat, fineCat) {
        try {
          const store = loadLocalOverrides();
          if (itemKey || asin || titleKey) {
            const entry = {
              highLevelCategory: highCat,
              fineGrainCategory: fineCat,
              isUserCustomized: true,
              updatedAt: Date.now()
            };
            if (itemKey) store.itemCategories[itemKey] = entry;
            if (asin) store.itemCategories['asin:' + asin] = entry;
            if (titleKey) store.itemCategories['title:' + titleKey] = entry;
          }
          if (highCat) {
            store.customHighCategories = sortAlphaClient([...(store.customHighCategories || []), highCat]);
          }
          if (!store.subCategoriesByHigh) store.subCategoriesByHigh = {};
          for (const [h, list] of Object.entries(subCategoriesByHigh)) {
            store.subCategoriesByHigh[h] = sortAlphaClient([...(store.subCategoriesByHigh[h] || []), ...(list || [])]);
          }
          localStorage.setItem(LOCAL_CACHE_KEY, JSON.stringify(store));

          // Also sync with extension storage if running inside extension context
          if (typeof chrome !== 'undefined' && chrome.storage && chrome.storage.local) {
            chrome.storage.local.get(['amzCategorizationCache_v2'], function (res) {
              const extCache = (res && res.amzCategorizationCache_v2) || {
                itemCategories: {},
                customHighCategories: [],
                subCategoriesByHigh: {},
                customRules: []
              };
              Object.assign(extCache.itemCategories, store.itemCategories);
              extCache.customHighCategories = sortAlphaClient([
                ...(extCache.customHighCategories || []),
                ...(store.customHighCategories || [])
              ]);
              if (!extCache.subCategoriesByHigh) extCache.subCategoriesByHigh = {};
              for (const [h, list] of Object.entries(store.subCategoriesByHigh)) {
                extCache.subCategoriesByHigh[h] = sortAlphaClient([
                  ...(extCache.subCategoriesByHigh[h] || []),
                  ...(list || [])
                ]);
              }
              chrome.storage.local.set({ amzCategorizationCache_v2: extCache });
            });
          }
        } catch (_) {}
      }

      /**
       * Remembers a Sub-Category as part of a High-Level Category (sorted alphabetically)
       * and updates all Sub-Category dropdowns for rows currently assigned to that High-Level Category.
       */
      function rememberSubCategory(highCat, fineCat) {
        if (!highCat) return;
        if (!subCategoriesByHigh[highCat]) {
          subCategoriesByHigh[highCat] = [];
        }
        if (fineCat && !subCategoriesByHigh[highCat].includes(fineCat)) {
          subCategoriesByHigh[highCat].push(fineCat);
        }
        if (subCategoriesByHigh[highCat].length === 0) {
          subCategoriesByHigh[highCat].push(highCat === 'UNCATEGORIZED' ? 'Uncategorized' : 'General ' + highCat);
        }
        subCategoriesByHigh[highCat] = sortAlphaClient(subCategoriesByHigh[highCat]);

        // Refresh all fine-category selects on rows that belong to highCat
        const fineSelects = Array.from(document.querySelectorAll('.js-fine-cat-select'));
        for (const sel of fineSelects) {
          const container = sel.closest('[data-high-category]');
          const rowHigh = container ? container.getAttribute('data-high-category') : '';
          if (rowHigh === highCat) {
            const currentVal = sel.value && sel.value !== '__CUSTOM_FINE__' ? sel.value : fineCat;
            populateSubCategorySelect(sel, highCat, currentVal);
          }
        }
      }

      /**
       * Populates a single Sub-Category <select> with the alphabetically-sorted sub-categories
       * remembered for highCat, plus '+ Custom Sub-Category...'.
       */
      function populateSubCategorySelect(selectEl, highCat, selectedFineCat) {
        if (!selectEl) return;
        const list = sortAlphaClient([
          ...(subCategoriesByHigh[highCat] || []),
          selectedFineCat || (highCat === 'UNCATEGORIZED' ? 'Uncategorized' : 'General ' + highCat)
        ]);
        subCategoriesByHigh[highCat] = list;
        selectEl.innerHTML = '';
        for (const sub of list) {
          const opt = document.createElement('option');
          opt.value = sub;
          opt.textContent = sub;
          if (sub === selectedFineCat) opt.selected = true;
          selectEl.appendChild(opt);
        }
        const customOpt = document.createElement('option');
        customOpt.value = '__CUSTOM_FINE__';
        customOpt.textContent = '+ Custom Sub-Category...';
        selectEl.appendChild(customOpt);
        if (selectedFineCat) {
          selectEl.value = selectedFineCat;
        }
      }

      /**
       * Adds a High-Level Category to all High-Level Category <select> dropdowns
       * and sorts the options alphabetically (keeping '+ Custom Category...' at the end).
       */
      function addCategoryOptionToAllSelects(newCat) {
        if (!newCat) return;
        if (!subCategoriesByHigh[newCat] || subCategoriesByHigh[newCat].length === 0) {
          subCategoriesByHigh[newCat] = ['General ' + newCat];
        }
        const selects = Array.from(document.querySelectorAll('.js-high-cat-select'));
        for (const sel of selects) {
          const currentVal = sel.value;
          const allValues = sortAlphaClient([
            ...Array.from(sel.options)
              .map((o) => o.value)
              .filter((v) => v && v !== '__CUSTOM__'),
            newCat
          ]);
          sel.innerHTML = '';
          for (const val of allValues) {
            const opt = document.createElement('option');
            opt.value = val;
            opt.textContent = val;
            if (val === currentVal) opt.selected = true;
            sel.appendChild(opt);
          }
          const customOpt = document.createElement('option');
          customOpt.value = '__CUSTOM__';
          customOpt.textContent = '+ Custom Category...';
          sel.appendChild(customOpt);
          if (currentVal && currentVal !== '__CUSTOM__') {
            sel.value = currentVal;
          }
        }
      }

      /**
       * Renders the Category & Sub-Category Totals table body (#category-totals-tbody)
       * and footer (#category-totals-tfoot) using the current categoryBreakdown.
       */
      function renderCategoryTotalsTabClient(breakdown) {
        if (!totalsTbody) return;
        const sortedCatList = [...breakdown];
        if (currentTotalsSort === 'spend-asc') {
          sortedCatList.sort(
            (a, b) => a.estimatedSpend - b.estimatedSpend || a.highLevelCategory.localeCompare(b.highLevelCategory)
          );
        } else if (currentTotalsSort === 'alpha-asc') {
          sortedCatList.sort((a, b) =>
            a.highLevelCategory.localeCompare(b.highLevelCategory, undefined, { sensitivity: 'base' })
          );
        } else {
          sortedCatList.sort(
            (a, b) => b.estimatedSpend - a.estimatedSpend || a.highLevelCategory.localeCompare(b.highLevelCategory)
          );
        }

        const safeTotal = TOTAL_SPENT_AMOUNT > 0 ? TOTAL_SPENT_AMOUNT : 1;
        let totalSubCount = 0;
        const htmlRows = [];

        for (const catEntry of sortedCatList) {
          const st = getStyleForCat(catEntry.highLevelCategory);
          const catPctTotal =
            TOTAL_SPENT_AMOUNT > 0 ? ((catEntry.estimatedSpend / safeTotal) * 100).toFixed(1) : '0.0';
          const subs = [...(catEntry.subCategories || [])];
          if (currentTotalsSort === 'spend-asc') {
            subs.sort(
              (a, b) => a.estimatedSpend - b.estimatedSpend || a.fineGrainCategory.localeCompare(b.fineGrainCategory)
            );
          } else if (currentTotalsSort === 'alpha-asc') {
            subs.sort((a, b) =>
              a.fineGrainCategory.localeCompare(b.fineGrainCategory, undefined, { sensitivity: 'base' })
            );
          } else {
            subs.sort(
              (a, b) => b.estimatedSpend - a.estimatedSpend || a.fineGrainCategory.localeCompare(b.fineGrainCategory)
            );
          }
          totalSubCount += subs.length;

          htmlRows.push(
            '<tr class="totals-high-row" data-totals-high="' +
              escapeHtmlClient(catEntry.highLevelCategory) +
              '">' +
              '<td><span class="totals-cat-badge" style="background:' +
              st.bg +
              ';color:' +
              st.text +
              ';border-color:' +
              st.border +
              ';">' +
              escapeHtmlClient(catEntry.highLevelCategory) +
              '</span></td>' +
              '<td><strong class="totals-all-subs-label">All Sub-Categories (' +
              subs.length +
              ')</strong></td>' +
              '<td class="totals-num-col"><strong>' +
              catEntry.orderCount +
              '</strong></td>' +
              '<td class="totals-num-col"><strong>' +
              catEntry.itemCount +
              '</strong></td>' +
              '<td class="totals-cost-col"><strong>' +
              escapeHtmlClient(catEntry.estimatedSpendFormatted) +
              '</strong></td>' +
              '<td class="totals-num-col"><strong>100.0%</strong></td>' +
              '<td class="totals-bar-col"><div class="totals-pct-wrap"><span class="totals-pct-text"><strong>' +
              catPctTotal +
              '%</strong></span><div class="totals-pct-bar-bg"><div class="totals-pct-bar-fill" style="width:' +
              Math.min(100, Number(catPctTotal)) +
              '%;background:' +
              st.text +
              ';"></div></div></div></td>' +
              '<td class="totals-action-col"><button type="button" class="totals-filter-btn" data-filter-high="' +
              escapeHtmlClient(catEntry.highLevelCategory) +
              '" data-filter-fine="">Filter Category →</button></td>' +
              '</tr>'
          );

          for (const sub of subs) {
            const subPctCat =
              catEntry.estimatedSpend > 0
                ? ((sub.estimatedSpend / catEntry.estimatedSpend) * 100).toFixed(1)
                : '0.0';
            const subPctTotal =
              TOTAL_SPENT_AMOUNT > 0 ? ((sub.estimatedSpend / safeTotal) * 100).toFixed(1) : '0.0';
            htmlRows.push(
              '<tr class="totals-sub-row" data-totals-high="' +
                escapeHtmlClient(catEntry.highLevelCategory) +
                '" data-totals-fine="' +
                escapeHtmlClient(sub.fineGrainCategory) +
                '">' +
                '<td class="totals-sub-indent"><span class="totals-sub-arrow">↳</span><span class="totals-sub-parent-name">' +
                escapeHtmlClient(catEntry.highLevelCategory) +
                '</span></td>' +
                '<td><span class="totals-sub-badge">' +
                escapeHtmlClient(sub.fineGrainCategory) +
                '</span></td>' +
                '<td class="totals-num-col">' +
                sub.orderCount +
                '</td>' +
                '<td class="totals-num-col">' +
                sub.itemCount +
                '</td>' +
                '<td class="totals-cost-col">' +
                escapeHtmlClient(sub.estimatedSpendFormatted) +
                '</td>' +
                '<td class="totals-num-col">' +
                subPctCat +
                '%</td>' +
                '<td class="totals-bar-col"><div class="totals-pct-wrap"><span class="totals-pct-text">' +
                subPctTotal +
                '%</span><div class="totals-pct-bar-bg"><div class="totals-pct-bar-fill" style="width:' +
                Math.min(100, Number(subPctTotal)) +
                '%;background:#007185;"></div></div></div></td>' +
                '<td class="totals-action-col"><button type="button" class="totals-filter-btn totals-filter-sub-btn" data-filter-high="' +
                escapeHtmlClient(catEntry.highLevelCategory) +
                '" data-filter-fine="' +
                escapeHtmlClient(sub.fineGrainCategory) +
                '">Filter Sub-Cat →</button></td>' +
                '</tr>'
            );
          }
        }

        totalsTbody.innerHTML = htmlRows.join('\\n');
        if (totalsTfoot) {
          totalsTfoot.innerHTML =
            '<tr>' +
            '<td>Grand Total (' +
            breakdown.length +
            ' Categories)</td>' +
            '<td>' +
            totalSubCount +
            ' Active Sub-Categories</td>' +
            '<td class="totals-num-col">' +
            TOTAL_ORDERS_COUNT +
            '</td>' +
            '<td class="totals-num-col">' +
            TOTAL_ITEMS_COUNT +
            '</td>' +
            '<td class="totals-cost-col">' +
            escapeHtmlClient(REPORT_CURRENCY) +
            TOTAL_SPENT_AMOUNT.toFixed(2) +
            '</td>' +
            '<td class="totals-num-col">100.0%</td>' +
            '<td class="totals-bar-col">100.0% of Total Spend</td>' +
            '<td class="totals-action-col"></td>' +
            '</tr>';
        }
        if (totalsSummaryEl) {
          totalsSummaryEl.textContent =
            'Live totals across ' +
            breakdown.length +
            ' High-Level Categories and ' +
            totalSubCount +
            ' Sub-Categories (' +
            TOTAL_ITEMS_COUNT +
            ' items • ' +
            REPORT_CURRENCY +
            TOTAL_SPENT_AMOUNT.toFixed(2) +
            ')';
        }
      }

      /**
       * Dynamically recalculates total cost and item/order counts across all categories
       * and sub-categories, updating:
       * 1. Top Category Filter strip (#category-chips-container)
       * 2. Category & Sub-Category Totals Tab (#category-totals-tbody)
       * 3. Embedded #amazon-orders-data JSON payload
       */
      function recalculateAndRenderCategoryBreakdown() {
        const rows = Array.from(tbody.querySelectorAll('.report-table-row'));
        const statsMap = new Map();

        for (const row of rows) {
          const cat = row.getAttribute('data-high-category') || 'UNCATEGORIZED';
          const fine =
            row.getAttribute('data-fine-category') ||
            (cat === 'UNCATEGORIZED' ? 'Uncategorized' : 'General ' + cat);
          const orderId = row.getAttribute('data-order-id') || '';
          const itemCost = parseFloat(row.getAttribute('data-item-cost-num') || '0') || 0;

          const cur = statsMap.get(cat) || {
            category: cat,
            itemCount: 0,
            orderIds: new Set(),
            spend: 0,
            subMap: new Map()
          };
          cur.itemCount += 1;
          if (orderId) cur.orderIds.add(orderId);
          cur.spend += itemCost;

          const subCur = cur.subMap.get(fine) || {
            fineGrainCategory: fine,
            highLevelCategory: cat,
            itemCount: 0,
            orderIds: new Set(),
            spend: 0
          };
          subCur.itemCount += 1;
          if (orderId) subCur.orderIds.add(orderId);
          subCur.spend += itemCost;
          cur.subMap.set(fine, subCur);

          statsMap.set(cat, cur);
        }

        const breakdown = Array.from(statsMap.values())
          .map((c) => {
            const subCategories = Array.from(c.subMap.values())
              .map((s) => ({
                highLevelCategory: c.category,
                fineGrainCategory: s.fineGrainCategory,
                orderCount: s.orderIds.size,
                itemCount: s.itemCount,
                estimatedSpend: Number(s.spend.toFixed(2)),
                estimatedSpendFormatted: REPORT_CURRENCY + s.spend.toFixed(2)
              }))
              .sort(
                (a, b) =>
                  b.estimatedSpend - a.estimatedSpend ||
                  a.fineGrainCategory.localeCompare(b.fineGrainCategory, undefined, { sensitivity: 'base' })
              );
            return {
              highLevelCategory: c.category,
              orderCount: c.orderIds.size,
              itemCount: c.itemCount,
              estimatedSpend: Number(c.spend.toFixed(2)),
              estimatedSpendFormatted: REPORT_CURRENCY + c.spend.toFixed(2),
              subCategories
            };
          })
          .sort((a, b) => b.estimatedSpend - a.estimatedSpend || a.highLevelCategory.localeCompare(b.highLevelCategory));

        // If the active category filter no longer has any items, fall back to ALL
        if (activeCategory !== 'ALL' && !statsMap.has(activeCategory)) {
          activeCategory = 'ALL';
          activeSubCategory = '';
        }

        // Re-render category filter chips with updated counts & dollar totals
        const chipsHtml = [
          '<button type="button" class="cat-filter-chip ' +
            (activeCategory === 'ALL' ? 'active' : '') +
            '" data-cat="ALL">' +
            'All Categories <span>(' +
            TOTAL_ORDERS_COUNT +
            ' orders • ' +
            escapeHtmlClient(REPORT_CURRENCY) +
            TOTAL_SPENT_AMOUNT.toFixed(2) +
            ')</span></button>',
          ...breakdown.map((c) => {
            const st = getStyleForCat(c.highLevelCategory);
            const isActive = activeCategory === c.highLevelCategory ? ' active' : '';
            return (
              '<button type="button" class="cat-filter-chip' +
              isActive +
              '" data-cat="' +
              escapeHtmlClient(c.highLevelCategory) +
              '" style="--chip-bg:' +
              st.bg +
              ';--chip-text:' +
              st.text +
              ';--chip-border:' +
              st.border +
              ';">' +
              escapeHtmlClient(c.highLevelCategory) +
              ' <span>(' +
              c.itemCount +
              ' • ' +
              escapeHtmlClient(c.estimatedSpendFormatted) +
              ')</span></button>'
            );
          })
        ].join('\\n        ');

        if (chipsContainer) {
          chipsContainer.innerHTML = chipsHtml;
        }

        // Re-render Category & Sub-Category Totals Tab
        renderCategoryTotalsTabClient(breakdown);

        // Update embedded JSON reportSummary
        const data = getPayloadData();
        if (data && data.reportSummary) {
          data.reportSummary.categoryBreakdown = breakdown;
          data.reportSummary.subCategoryBreakdown = breakdown.flatMap((c) => c.subCategories || []);
          data.reportSummary.subCategoriesByHigh = subCategoriesByHigh;
          data.reportSummary.availableHighLevelCategories = sortAlphaClient([
            ...(data.reportSummary.availableHighLevelCategories || []),
            ...Object.keys(subCategoriesByHigh)
          ]);
          savePayloadData(data);
        }

        applyFilter();
      }

      /**
       * Sorts both the Itemized Order Table and the Order Cards View by:
       * - 'placed' (Order Placed Date)
       * - 'delivery' (Delivery Date)
       * - 'cost' (Item Cost / Order Total Cost)
       */
      function applySort(field, dir) {
        currentSortField = field;
        currentSortDir = dir;

        const mult = dir === 'asc' ? 1 : -1;

        const rows = Array.from(tbody.querySelectorAll('.report-table-row'));
        rows.sort((a, b) => {
          if (field === 'cost') {
            const ca = parseFloat(a.getAttribute('data-item-cost-num') || a.getAttribute('data-cost-num') || '0') || 0;
            const cb = parseFloat(b.getAttribute('data-item-cost-num') || b.getAttribute('data-cost-num') || '0') || 0;
            if (Math.abs(ca - cb) > 0.0001) return (ca - cb) * mult;
            const oa = parseFloat(a.getAttribute('data-cost-num') || '0') || 0;
            const ob = parseFloat(b.getAttribute('data-cost-num') || '0') || 0;
            if (Math.abs(oa - ob) > 0.0001) return (oa - ob) * mult;
            return (a.getAttribute('data-order-id') || '').localeCompare(b.getAttribute('data-order-id') || '');
          }
          const attrName = field === 'delivery' ? 'data-delivery-iso-date' : 'data-order-iso-date';
          const da = a.getAttribute(attrName) || a.getAttribute('data-order-iso-date') || '';
          const db = b.getAttribute(attrName) || b.getAttribute('data-order-iso-date') || '';
          if (da !== db) return da.localeCompare(db) * mult;
          return (a.getAttribute('data-order-id') || '').localeCompare(b.getAttribute('data-order-id') || '');
        });
        rows.forEach((r) => tbody.appendChild(r));

        const cards = Array.from(cardsSec.querySelectorAll('.order-card'));
        cards.sort((a, b) => {
          if (field === 'cost') {
            const ca = parseFloat(a.getAttribute('data-order-total-num') || '0') || 0;
            const cb = parseFloat(b.getAttribute('data-order-total-num') || '0') || 0;
            if (Math.abs(ca - cb) > 0.0001) return (ca - cb) * mult;
            return (a.getAttribute('data-order-id') || '').localeCompare(b.getAttribute('data-order-id') || '');
          }
          const attrName = field === 'delivery' ? 'data-delivery-iso-date' : 'data-order-iso-date';
          const da = a.getAttribute(attrName) || a.getAttribute('data-order-iso-date') || '';
          const db = b.getAttribute(attrName) || b.getAttribute('data-order-iso-date') || '';
          if (da !== db) return da.localeCompare(db) * mult;
          return (a.getAttribute('data-order-id') || '').localeCompare(b.getAttribute('data-order-id') || '');
        });
        cards.forEach((c) => cardsSec.appendChild(c));

        sortBtns.forEach((btn) => {
          const match = btn.getAttribute('data-sort-field') === field && btn.getAttribute('data-sort-dir') === dir;
          btn.classList.toggle('active', match);
        });
        if (thSortPlaced) thSortPlaced.classList.toggle('active', field === 'placed');
        if (thSortDelivery) thSortDelivery.classList.toggle('active', field === 'delivery');
        if (thSortCost) thSortCost.classList.toggle('active', field === 'cost');
      }

      /**
       * Evaluates whether a numeric cost value matches a cost expression (e.g. ">50", "<=25", "20-100", "49.99")
       * and optional minCost / maxCost bounds.
       */
      function matchesNumericCost(costVal, exprRaw, minVal, maxVal) {
        if (!Number.isNaN(minVal) && minVal !== null && costVal < minVal) return false;
        if (!Number.isNaN(maxVal) && maxVal !== null && costVal > maxVal) return false;
        if (!exprRaw) return true;

        const expr = exprRaw.replace(/[$£€,\\s]/g, '');
        if (!expr) return true;

        // Range e.g. 20-100 or 20..100
        const rangeMatch = expr.match(/^(\\d+(?:\\.\\d+)?)(?:-|\\.\\.|to)(\\d+(?:\\.\\d+)?)$/i);
        if (rangeMatch) {
          const low = parseFloat(rangeMatch[1]);
          const high = parseFloat(rangeMatch[2]);
          return costVal >= Math.min(low, high) && costVal <= Math.max(low, high);
        }

        // Comparison operator e.g. >=50, >50, <=25, <25, =49.99
        const opMatch = expr.match(/^(>=|<=|>|<|=|==)(\\d+(?:\\.\\d+)?)$/);
        if (opMatch) {
          const op = opMatch[1];
          const target = parseFloat(opMatch[2]);
          if (op === '>=') return costVal >= target;
          if (op === '>') return costVal > target;
          if (op === '<=') return costVal <= target;
          if (op === '<') return costVal < target;
          return Math.abs(costVal - target) < 0.01;
        }

        // Exact or substring numeric match
        const numTarget = parseFloat(expr);
        if (!Number.isNaN(numTarget)) {
          if (Math.abs(costVal - numTarget) < 0.01) return true;
          if (costVal.toFixed(2).includes(expr)) return true;
        }
        return false;
      }

      function updateSubCatPillUI() {
        if (!activeSubcatPill || !activeSubcatText) return;
        if (activeSubCategory) {
          activeSubcatPill.style.display = 'inline-flex';
          activeSubcatText.textContent =
            (activeCategory !== 'ALL' ? activeCategory + ' › ' : '') + activeSubCategory;
        } else {
          activeSubcatPill.style.display = 'none';
          activeSubcatText.textContent = '';
        }
      }

      /**
       * Filters both the Categorized Item Table and Order Cards by:
       * - Item / Keyword search (#order-search-input) — also supports inline cost expressions like >50 or 20-100
       * - Dedicated Cost Filter expression (#cost-filter-input)
       * - Min Cost (#cost-min-input) and Max Cost (#cost-max-input)
       * - Active High-Level Category chip and optional Sub-Category filter
       */
      function applyFilter() {
        const rawSearch = (searchInput ? searchInput.value : '').trim();
        let textQuery = rawSearch.toLowerCase();
        let costExpr = (costFilterInput ? costFilterInput.value : '').trim();

        // If user typed a cost expression like ">50", "<25", or "20-100" directly into the search input,
        // treat it as a cost filter expression automatically!
        if (!costExpr && /^(?:>=|<=|>|<|=|\\$)\\s*\\d|^\\$?\\d+(?:\\.\\d+)?\\s*(?:-|\\.\\.)\\s*\\$?\\d/.test(rawSearch)) {
          costExpr = rawSearch;
          textQuery = '';
        }

        const minVal =
          costMinInput && costMinInput.value !== '' ? parseFloat(costMinInput.value) : null;
        const maxVal =
          costMaxInput && costMaxInput.value !== '' ? parseFloat(costMaxInput.value) : null;

        const rows = Array.from(tbody.querySelectorAll('.report-table-row'));
        const visibleOrderIdsFromRows = new Set();
        let visibleItemsCount = 0;
        let filteredSpendTotal = 0;

        for (const row of rows) {
          const text = row.textContent.toLowerCase();
          const rowCat = row.getAttribute('data-high-category') || '';
          const rowFine = row.getAttribute('data-fine-category') || '';
          const itemCost = parseFloat(row.getAttribute('data-item-cost-num') || '0') || 0;
          const orderCost = parseFloat(row.getAttribute('data-cost-num') || '0') || 0;

          const matchesText = !textQuery || text.includes(textQuery);
          const matchesCat = activeCategory === 'ALL' || rowCat === activeCategory;
          const matchesFine = !activeSubCategory || rowFine === activeSubCategory;
          const matchesCost =
            matchesNumericCost(itemCost, costExpr, minVal, maxVal) ||
            matchesNumericCost(orderCost, costExpr, minVal, maxVal);

          const show = matchesText && matchesCat && matchesFine && matchesCost;
          row.style.display = show ? '' : 'none';
          if (show) {
            visibleItemsCount++;
            filteredSpendTotal += itemCost;
            const oid = row.getAttribute('data-order-id');
            if (oid) visibleOrderIdsFromRows.add(oid);
          }
        }

        const cards = Array.from(cardsSec.querySelectorAll('.order-card'));
        let visibleCards = 0;

        for (const card of cards) {
          const text = card.textContent.toLowerCase();
          const cats = (card.getAttribute('data-high-categories') || '').split('|');
          const fines = (card.getAttribute('data-fine-categories') || '').split('|');
          const orderTotalNum = parseFloat(card.getAttribute('data-order-total-num') || '0') || 0;
          const itemEls = Array.from(card.querySelectorAll('.order-item-row'));
          const anyItemMatchesCost = itemEls.some((el) =>
            matchesNumericCost(parseFloat(el.getAttribute('data-item-cost-num') || '0') || 0, costExpr, minVal, maxVal)
          );

          const matchesText = !textQuery || text.includes(textQuery);
          const matchesCat = activeCategory === 'ALL' || cats.includes(activeCategory);
          const matchesFine = !activeSubCategory || fines.includes(activeSubCategory);
          const matchesCost =
            matchesNumericCost(orderTotalNum, costExpr, minVal, maxVal) || anyItemMatchesCost;

          const show = matchesText && matchesCat && matchesFine && matchesCost;
          card.style.display = show ? '' : 'none';
          if (show) visibleCards++;
        }

        const displayedOrdersCount = visibleOrderIdsFromRows.size || visibleCards;
        if (countEl) {
          countEl.textContent =
            displayedOrdersCount + (displayedOrdersCount === 1 ? ' order' : ' orders');
        }
        if (itemCountEl) {
          itemCountEl.textContent =
            visibleItemsCount + (visibleItemsCount === 1 ? ' item' : ' items');
        }
        if (spendSummaryEl) {
          spendSummaryEl.innerHTML =
            '• Filtered Cost: <strong>' +
            escapeHtmlClient(REPORT_CURRENCY) +
            filteredSpendTotal.toFixed(2) +
            '</strong> (Report Total: <strong>' +
            escapeHtmlClient(REPORT_CURRENCY) +
            TOTAL_SPENT_AMOUNT.toFixed(2) +
            '</strong> across <strong>' +
            PAGES_FETCHED_COUNT +
            (PAGES_FETCHED_COUNT === 1 ? ' page' : ' pages') +
            '</strong>)';
        }
        updateSubCatPillUI();
      }

      function updateItemCategorizationInDomAndJson(itemKey, newHighCat, newFineCat, shouldSaveStorage = true) {
        const matchingEls = Array.from(
          document.querySelectorAll('[data-item-key="' + itemKey.replace(/"/g, '\\"') + '"]')
        );
        let asin = '';
        let titleKey = '';
        let resolvedHigh = newHighCat;
        let resolvedFine = newFineCat;

        for (const el of matchingEls) {
          if (el.getAttribute('data-asin')) asin = el.getAttribute('data-asin');
          if (el.getAttribute('data-title-key')) titleKey = el.getAttribute('data-title-key');
          if (!resolvedHigh && el.getAttribute('data-high-category')) {
            resolvedHigh = el.getAttribute('data-high-category');
          }
          if (!resolvedFine && el.getAttribute('data-fine-category')) {
            resolvedFine = el.getAttribute('data-fine-category');
          }
        }

        if (!resolvedHigh) resolvedHigh = 'UNCATEGORIZED';

        // If High-Level Category changed and no explicit Sub-Category was passed,
        // pick an alphabetical Sub-Category belonging to resolvedHigh
        if (newHighCat && !newFineCat) {
          const subsForHigh = subCategoriesByHigh[resolvedHigh] || [];
          if (!subsForHigh.includes(resolvedFine)) {
            resolvedFine =
              subsForHigh[0] || (resolvedHigh === 'UNCATEGORIZED' ? 'Uncategorized' : 'General ' + resolvedHigh);
          }
        }
        if (!resolvedFine) {
          resolvedFine = resolvedHigh === 'UNCATEGORIZED' ? 'Uncategorized' : 'General ' + resolvedHigh;
        }

        addCategoryOptionToAllSelects(resolvedHigh);
        rememberSubCategory(resolvedHigh, resolvedFine);

        const st = getStyleForCat(resolvedHigh);

        for (const el of matchingEls) {
          if (el.hasAttribute('data-high-category')) {
            el.setAttribute('data-high-category', resolvedHigh);
          }
          if (el.hasAttribute('data-fine-category')) {
            el.setAttribute('data-fine-category', resolvedFine);
          }
          if (el.classList.contains('js-high-cat-select')) {
            el.value = resolvedHigh;
            el.style.background = st.bg;
            el.style.color = st.text;
            el.style.borderColor = st.border;
          }
          if (el.classList.contains('js-fine-cat-select')) {
            populateSubCategorySelect(el, resolvedHigh, resolvedFine);
          }
        }

        // Update parent order card data-high-categories & data-fine-categories
        const cards = Array.from(cardsSec.querySelectorAll('.order-card'));
        for (const card of cards) {
          const itemRows = Array.from(card.querySelectorAll('.order-item-row'));
          const cats = Array.from(
            new Set(itemRows.map((r) => r.getAttribute('data-high-category') || 'UNCATEGORIZED'))
          );
          const fines = Array.from(
            new Set(itemRows.map((r) => r.getAttribute('data-fine-category') || 'Uncategorized'))
          );
          if (cats.length > 0) {
            card.setAttribute('data-high-categories', cats.join('|'));
            card.setAttribute('data-high-category', cats[0]);
          }
          if (fines.length > 0) {
            card.setAttribute('data-fine-categories', fines.join('|'));
            card.setAttribute('data-fine-category', fines[0]);
          }
        }

        // Update embedded JSON payload for AI agent ingestion
        const data = getPayloadData();
        if (data && Array.isArray(data.orders)) {
          for (const o of data.orders) {
            for (const it of o.items || []) {
              if (it.itemKey === itemKey) {
                it.highLevelCategory = resolvedHigh;
                it.fineGrainCategory = resolvedFine;
                it.isUserCustomized = true;
                if (it.toolKeywordAnalysis) {
                  it.toolKeywordAnalysis.classificationSource =
                    'User Customized Override (' + resolvedHigh + ' › ' + resolvedFine + ')';
                }
                o.highLevelCategory = resolvedHigh;
                o.fineGrainCategory = resolvedFine;
              }
            }
          }
          savePayloadData(data);
        }

        // Recalculate total cost across categories & sub-categories
        recalculateAndRenderCategoryBreakdown();

        if (shouldSaveStorage) {
          persistOverridesToStorage(itemKey, asin, titleKey, resolvedHigh, resolvedFine);
        }
      }

      // ============================================================================
      // ITEM FORMATTED JSON & KEYWORDS INSPECTOR MODAL POPUP
      // ============================================================================
      const itemJsonBackdrop = document.getElementById('item-json-modal-backdrop');
      const itemJsonTitle = document.getElementById('item-json-modal-title');
      const itemJsonSub = document.getElementById('item-json-modal-sub');
      const itemJsonSourceBox = document.getElementById('item-json-source-box');
      const itemJsonMatchedKws = document.getElementById('item-json-matched-kws');
      const itemJsonExtractedKws = document.getElementById('item-json-extracted-kws');
      const itemJsonKeyLabel = document.getElementById('item-json-key-label');
      const itemJsonCode = document.getElementById('item-json-modal-code');
      const copyItemJsonBtn = document.getElementById('copy-item-json-btn');
      const closeItemJsonX = document.getElementById('close-item-json-modal-x');
      const closeItemJsonBtn = document.getElementById('close-item-json-modal-btn');
      let currentModalRawJson = '';

      function syntaxHighlightJson(jsonStr) {
        const escaped = escapeHtmlClient(jsonStr);
        return escaped.replace(
          /(&quot;(?:\\\\u[a-zA-Z0-9]{4}|\\\\[^u]|[^\\\\&])*&quot;(\\s*:)?|\\b(true|false|null)\\b|-?\\d+(?:\\.\\d*)?(?:[eE][+\\-]?\\d+)?)/g,
          function (match) {
            let cls = 'json-num';
            if (/^&quot;/.test(match)) {
              if (/:$/.test(match)) {
                cls = 'json-key';
              } else {
                cls = 'json-str';
              }
            } else if (/^(?:true|false)$/.test(match)) {
              cls = 'json-bool';
            } else if (/^null$/.test(match)) {
              cls = 'json-null';
            }
            return '<span class="' + cls + '">' + match + '</span>';
          }
        );
      }

      function findItemInPayload(itemKey) {
        const data = getPayloadData();
        if (!data || !Array.isArray(data.orders)) return null;
        for (const o of data.orders) {
          for (const it of o.items || []) {
            if (it.itemKey === itemKey) {
              return { item: it, order: o };
            }
          }
        }
        return null;
      }

      function openItemJsonModal(itemKey) {
        if (!itemJsonBackdrop) return;
        const found = findItemInPayload(itemKey);
        if (!found) return;

        const it = found.item;
        const ord = found.order;
        const st = getStyleForCat(it.highLevelCategory || 'UNCATEGORIZED');
        const kwAnalysis = it.toolKeywordAnalysis || {};
        const matchedList = Array.isArray(kwAnalysis.matchedKeywords) ? kwAnalysis.matchedKeywords : [];
        const extractedList = Array.isArray(kwAnalysis.allExtractedKeywords)
          ? kwAnalysis.allExtractedKeywords
          : [];

        if (itemJsonTitle) {
          itemJsonTitle.textContent = it.title || 'Amazon Order Item';
        }
        if (itemJsonSub) {
          itemJsonSub.innerHTML =
            '<span>Order #: <code>' +
            escapeHtmlClient(ord.orderNumber || ord.orderId || '') +
            '</code></span>' +
            (it.asin ? '<span>• ASIN: <code>' + escapeHtmlClient(it.asin) + '</code></span>' : '') +
            '<span>• Cost: <strong>' +
            escapeHtmlClient(it.cost || ord.cost || '') +
            '</strong></span>' +
            '<span>• Category: <span style="display:inline-block;padding:1px 8px;border-radius:99px;font-weight:700;background:' +
            st.bg +
            ';color:' +
            st.text +
            ';border:1px solid ' +
            st.border +
            ';">' +
            escapeHtmlClient(it.highLevelCategory || 'UNCATEGORIZED') +
            ' › ' +
            escapeHtmlClient(it.fineGrainCategory || 'Uncategorized') +
            '</span></span>';
        }

        if (itemJsonSourceBox) {
          const srcText = kwAnalysis.classificationSource || 'Taxonomy / Heuristic Evaluation';
          const confText = kwAnalysis.confidence || it.categoryConfidence || 'low';
          const scoreVal = typeof kwAnalysis.ruleScore === 'number' ? kwAnalysis.ruleScore : 0;
          itemJsonSourceBox.innerHTML =
            '<span class="kw-pill kw-pill-matched" style="cursor:default;">' +
            escapeHtmlClient(srcText) +
            '</span>' +
            '<span class="kw-pill" style="cursor:default;">Confidence: <strong>' +
            escapeHtmlClient(confText) +
            '</strong> (Score: ' +
            scoreVal +
            ')</span>';
        }

        if (itemJsonMatchedKws) {
          if (matchedList.length > 0) {
            itemJsonMatchedKws.innerHTML = matchedList
              .map(
                (kw) =>
                  '<button type="button" class="kw-pill kw-pill-matched js-kw-filter-pill" data-kw="' +
                  escapeHtmlClient(kw) +
                  '" title="Click to filter report by keyword: ' +
                  escapeHtmlClient(kw) +
                  '">✓ ' +
                  escapeHtmlClient(kw) +
                  '</button>'
              )
              .join('');
          } else {
            itemJsonMatchedKws.innerHTML =
              '<span class="kw-pill kw-pill-none">None — no built-in or custom taxonomy keywords matched</span>';
          }
        }

        if (itemJsonExtractedKws) {
          if (extractedList.length > 0) {
            itemJsonExtractedKws.innerHTML = extractedList
              .map(
                (kw) =>
                  '<button type="button" class="kw-pill js-kw-filter-pill" data-kw="' +
                  escapeHtmlClient(kw) +
                  '" title="Click to filter report by keyword: ' +
                  escapeHtmlClient(kw) +
                  '">' +
                  escapeHtmlClient(kw) +
                  '</button>'
              )
              .join('');
          } else {
            itemJsonExtractedKws.innerHTML = '<span style="color:#565959;">No keywords extracted</span>';
          }
        }

        if (itemJsonKeyLabel) {
          itemJsonKeyLabel.textContent = 'itemKey: ' + (it.itemKey || itemKey);
        }

        currentModalRawJson = JSON.stringify(it, null, 2);
        if (itemJsonCode) {
          itemJsonCode.innerHTML = syntaxHighlightJson(currentModalRawJson);
        }

        itemJsonBackdrop.classList.add('open');
      }

      function closeItemJsonModal() {
        if (itemJsonBackdrop) {
          itemJsonBackdrop.classList.remove('open');
        }
      }

      // Click delegation for "{ } Item JSON & Keywords" links and keyword pills
      document.addEventListener('click', function (e) {
        const jsonLink = e.target.closest('.js-view-item-json');
        if (jsonLink) {
          e.preventDefault();
          const key = jsonLink.getAttribute('data-item-key');
          if (key) openItemJsonModal(key);
          return;
        }

        const kwPill = e.target.closest('.js-kw-filter-pill');
        if (kwPill) {
          const kw = kwPill.getAttribute('data-kw') || '';
          if (kw && searchInput) {
            searchInput.value = kw;
            closeItemJsonModal();
            applyFilter();
          }
          return;
        }
      });

      if (closeItemJsonX) closeItemJsonX.addEventListener('click', closeItemJsonModal);
      if (closeItemJsonBtn) closeItemJsonBtn.addEventListener('click', closeItemJsonModal);
      if (itemJsonBackdrop) {
        itemJsonBackdrop.addEventListener('click', function (e) {
          if (e.target === itemJsonBackdrop) closeItemJsonModal();
        });
      }
      document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && itemJsonBackdrop && itemJsonBackdrop.classList.contains('open')) {
          closeItemJsonModal();
        }
      });

      if (copyItemJsonBtn) {
        copyItemJsonBtn.addEventListener('click', function () {
          if (!currentModalRawJson) return;
          navigator.clipboard.writeText(currentModalRawJson).then(function () {
            const orig = copyItemJsonBtn.textContent;
            copyItemJsonBtn.textContent = '✓ Copied Item JSON!';
            setTimeout(function () {
              copyItemJsonBtn.textContent = orig;
            }, 2000);
          });
        });
      }

      // Event delegation for High-Level Category <select> & Sub-Category <select> changes
      document.addEventListener('change', function (e) {
        const target = e.target;
        if (!target) return;

        // 1. High-Level Category dropdown changed
        if (target.classList.contains('js-high-cat-select')) {
          const itemKey = target.getAttribute('data-item-key');
          const row = target.closest('[data-high-category]');
          const prevHigh = (row && row.getAttribute('data-high-category')) || 'UNCATEGORIZED';
          let chosenHigh = target.value;

          if (chosenHigh === '__CUSTOM__') {
            const customName = window.prompt('Enter new High-Level Category name:');
            if (!customName || !customName.trim()) {
              target.value = prevHigh;
              return;
            }
            chosenHigh = customName.trim();
            addCategoryOptionToAllSelects(chosenHigh);
          }

          updateItemCategorizationInDomAndJson(itemKey, chosenHigh, null, true);
          return;
        }

        // 2. Sub-Category (Finer-Grain Category) dropdown changed
        if (target.classList.contains('js-fine-cat-select')) {
          const itemKey = target.getAttribute('data-item-key');
          const row = target.closest('[data-high-category]');
          const currentHigh = (row && row.getAttribute('data-high-category')) || 'UNCATEGORIZED';
          const prevFine = (row && row.getAttribute('data-fine-category')) || 'Uncategorized';
          let chosenFine = target.value;

          if (chosenFine === '__CUSTOM_FINE__') {
            const customSub = window.prompt(
              'Enter new Sub-Category to remember under "' + currentHigh + '":',
              prevFine
            );
            if (!customSub || !customSub.trim()) {
              target.value = prevFine;
              return;
            }
            chosenFine = customSub.trim();
          }

          rememberSubCategory(currentHigh, chosenFine);
          updateItemCategorizationInDomAndJson(itemKey, currentHigh, chosenFine, true);
        }
      });

      // Add Custom Category button
      document.getElementById('add-custom-cat-btn').addEventListener('click', function () {
        const newCat = window.prompt('Enter new High-Level Category to add (sorted alphabetically):');
        if (newCat && newCat.trim()) {
          const trimmed = newCat.trim();
          addCategoryOptionToAllSelects(trimmed);
          persistOverridesToStorage(null, null, null, trimmed, null);
        }
      });

      // Add Custom Sub-Category button
      document.getElementById('add-custom-subcat-btn').addEventListener('click', function () {
        const defaultHigh = activeCategory !== 'ALL' ? activeCategory : 'Grocery';
        const targetHigh = window.prompt(
          'Which High-Level Category should this Sub-Category belong to?',
          defaultHigh
        );
        if (!targetHigh || !targetHigh.trim()) return;
        const cleanHigh = targetHigh.trim();
        const newSub = window.prompt('Enter new Sub-Category name for "' + cleanHigh + '":');
        if (!newSub || !newSub.trim()) return;
        const cleanSub = newSub.trim();
        addCategoryOptionToAllSelects(cleanHigh);
        rememberSubCategory(cleanHigh, cleanSub);
        recalculateAndRenderCategoryBreakdown();
        persistOverridesToStorage(null, null, null, cleanHigh, cleanSub);
      });

      // Save Updated HTML Report button
      document.getElementById('save-updated-html-btn').addEventListener('click', function () {
        document.querySelectorAll('.js-high-cat-select, .js-fine-cat-select').forEach((sel) => {
          Array.from(sel.options).forEach((opt) => {
            if (opt.value === sel.value) opt.setAttribute('selected', 'selected');
            else opt.removeAttribute('selected');
          });
        });
        const fullHtml = '<!DOCTYPE html>\\n' + document.documentElement.outerHTML;
        const blob = new Blob([fullHtml], { type: 'text/html;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'amazon-orders-${escapeHtml(yearLabel)}-customized-report.html';
        a.click();
        URL.revokeObjectURL(url);
      });

      // Copy Category Cache token for Extension import
      document.getElementById('copy-cache-sync-btn').addEventListener('click', function () {
        const store = loadLocalOverrides();
        navigator.clipboard.writeText(JSON.stringify(store, null, 2)).then(function () {
          const btn = document.getElementById('copy-cache-sync-btn');
          const orig = btn.textContent;
          btn.textContent = '✓ Copied Cache!';
          setTimeout(function () { btn.textContent = orig; }, 2000);
        });
      });

      // Sync top & table horizontal scrollbars if window is resized narrow
      function syncTopScrollWidth() {
        if (!topScroll || !topScrollInner || !tableWrap || !mainTable) return;
        const sw = mainTable.scrollWidth;
        const cw = tableWrap.clientWidth;
        if (sw > cw + 2) {
          topScroll.style.display = 'block';
          topScrollInner.style.width = sw + 'px';
        } else {
          topScroll.style.display = 'none';
        }
      }
      if (topScroll && tableWrap) {
        topScroll.addEventListener('scroll', function () {
          tableWrap.scrollLeft = topScroll.scrollLeft;
        });
        tableWrap.addEventListener('scroll', function () {
          topScroll.scrollLeft = tableWrap.scrollLeft;
        });
        window.addEventListener('resize', syncTopScrollWidth);
        setTimeout(syncTopScrollWidth, 100);
      }

      // Sort button listeners (Order Placed, Delivery Date, Cost)
      sortBtns.forEach((btn) => {
        btn.addEventListener('click', function () {
          applySort(btn.getAttribute('data-sort-field'), btn.getAttribute('data-sort-dir'));
        });
      });

      if (thSortPlaced) {
        thSortPlaced.addEventListener('click', function () {
          const nextDir = currentSortField === 'placed' && currentSortDir === 'desc' ? 'asc' : 'desc';
          applySort('placed', nextDir);
        });
      }

      if (thSortDelivery) {
        thSortDelivery.addEventListener('click', function () {
          const nextDir = currentSortField === 'delivery' && currentSortDir === 'desc' ? 'asc' : 'desc';
          applySort('delivery', nextDir);
        });
      }

      if (thSortCost) {
        thSortCost.addEventListener('click', function () {
          const nextDir = currentSortField === 'cost' && currentSortDir === 'desc' ? 'asc' : 'desc';
          applySort('cost', nextDir);
        });
      }

      // Totals tab sort buttons
      totalsSortBtns.forEach((btn) => {
        btn.addEventListener('click', function () {
          totalsSortBtns.forEach((b) => b.classList.remove('active'));
          btn.classList.add('active');
          currentTotalsSort = btn.getAttribute('data-totals-sort') || 'spend-desc';
          const data = getPayloadData();
          if (data && data.reportSummary && Array.isArray(data.reportSummary.categoryBreakdown)) {
            renderCategoryTotalsTabClient(data.reportSummary.categoryBreakdown);
          }
        });
      });

      // Totals tab "Filter Category / Filter Sub-Cat" button delegation
      if (totalsTbody) {
        totalsTbody.addEventListener('click', function (e) {
          const btn = e.target.closest('.totals-filter-btn');
          if (!btn) return;
          const targetHigh = btn.getAttribute('data-filter-high') || 'ALL';
          const targetFine = btn.getAttribute('data-filter-fine') || '';
          activeCategory = targetHigh;
          activeSubCategory = targetFine;

          document.querySelectorAll('.cat-filter-chip').forEach((c) => {
            c.classList.toggle('active', c.getAttribute('data-cat') === activeCategory);
          });

          // Switch to Categorized Thumbnail Table + Cards view so user sees the filtered items immediately
          tabs.forEach((t) => t.classList.toggle('active', t.getAttribute('data-view') === 'both'));
          if (totalsSec) totalsSec.style.display = 'none';
          if (tableSec) tableSec.style.display = 'block';
          if (cardsSec) cardsSec.style.display = 'block';

          applyFilter();
          syncTopScrollWidth();
        });
      }

      // Search & Cost filter input listeners
      if (searchInput) searchInput.addEventListener('input', applyFilter);
      if (costFilterInput) costFilterInput.addEventListener('input', applyFilter);
      if (costMinInput) costMinInput.addEventListener('input', applyFilter);
      if (costMaxInput) costMaxInput.addEventListener('input', applyFilter);
      if (searchBtn) searchBtn.addEventListener('click', applyFilter);

      if (clearSubcatBtn) {
        clearSubcatBtn.addEventListener('click', function () {
          activeSubCategory = '';
          applyFilter();
        });
      }

      if (clearFiltersBtn) {
        clearFiltersBtn.addEventListener('click', function () {
          if (searchInput) searchInput.value = '';
          if (costFilterInput) costFilterInput.value = '';
          if (costMinInput) costMinInput.value = '';
          if (costMaxInput) costMaxInput.value = '';
          activeCategory = 'ALL';
          activeSubCategory = '';
          document.querySelectorAll('.cat-filter-chip').forEach((c) => {
            c.classList.toggle('active', c.getAttribute('data-cat') === 'ALL');
          });
          applyFilter();
        });
      }

      chipsContainer.addEventListener('click', function (e) {
        const chip = e.target.closest('.cat-filter-chip');
        if (!chip) return;
        document.querySelectorAll('.cat-filter-chip').forEach((c) => c.classList.remove('active'));
        chip.classList.add('active');
        activeCategory = chip.getAttribute('data-cat') || 'ALL';
        activeSubCategory = '';
        applyFilter();
      });

      for (const tab of tabs) {
        tab.addEventListener('click', function () {
          tabs.forEach((t) => t.classList.remove('active'));
          tab.classList.add('active');
          const view = tab.getAttribute('data-view');
          if (totalsSec) {
            totalsSec.style.display = view === 'totals' ? 'block' : 'none';
          }
          tableSec.style.display = view === 'both' || view === 'table' ? 'block' : 'none';
          cardsSec.style.display = view === 'both' || view === 'cards' ? 'block' : 'none';
          syncTopScrollWidth();
        });
      }

      document.getElementById('copy-agent-json-btn').addEventListener('click', function () {
        const jsonEl = document.getElementById('amazon-orders-data');
        if (!jsonEl) return;
        navigator.clipboard.writeText(jsonEl.textContent.trim()).then(function () {
          const btn = document.getElementById('copy-agent-json-btn');
          const orig = btn.textContent;
          btn.textContent = '✓ Copied JSON!';
          setTimeout(function () { btn.textContent = orig; }, 2000);
        });
      });

      document.getElementById('download-csv-btn').addEventListener('click', function () {
        const data = getPayloadData();
        if (!data) return;
        const rows = [[
          'Order Placed Date',
          'Delivery Date',
          'Order Number',
          'Item Title',
          'Cost',
          'High-Level Category',
          'Sub-Category (Finer-Grain)',
          'ASIN',
          'Quantity',
          'Return Window',
          'Auto-Delivered',
          'Product URL'
        ]];
        for (const o of data.orders || []) {
          for (const it of o.items || [{}]) {
            rows.push([
              o.orderPlacedDate || '',
              it.deliveryDate || o.deliveryDate || '',
              o.orderNumber || o.orderId || '',
              it.title || '',
              it.cost || o.cost || '',
              it.highLevelCategory || o.highLevelCategory || '',
              it.fineGrainCategory || o.fineGrainCategory || '',
              it.asin || '',
              it.quantity || 1,
              it.returnWindow || '',
              it.autoDelivered || '',
              it.productUrl || ''
            ]);
          }
        }
        const csvContent = rows
          .map((r) => r.map((v) => '"' + String(v).replace(/"/g, '""') + '"').join(','))
          .join('\\n');
        const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'amazon-orders-${escapeHtml(yearLabel)}.csv';
        a.click();
        URL.revokeObjectURL(url);
      });

      // Re-apply any saved local overrides & remembered sub-categories on open
      const savedStore = loadLocalOverrides();
      if (savedStore) {
        (savedStore.customHighCategories || []).forEach((c) => addCategoryOptionToAllSelects(c));
        if (savedStore.subCategoriesByHigh && typeof savedStore.subCategoriesByHigh === 'object') {
          for (const [h, list] of Object.entries(savedStore.subCategoriesByHigh)) {
            addCategoryOptionToAllSelects(h);
            for (const sub of list || []) {
              rememberSubCategory(h, sub);
            }
          }
        }
        if (savedStore.itemCategories) {
          for (const [k, v] of Object.entries(savedStore.itemCategories)) {
            if (k.startsWith('order:') && v && v.isUserCustomized) {
              updateItemCategorizationInDomAndJson(k, v.highLevelCategory, v.fineGrainCategory, false);
            }
          }
        }
      }
    })();
  </script>
</body>
</html>`;
  }

  global.AmazonOrdersReportBuilder = {
    DEFAULT_HIGH_CATEGORIES,
    sortAlpha,
    resolveUrl,
    cleanText,
    normalizeTitleKey,
    extractKeywordsFromText,
    categorizeProduct,
    isLateNovEarlyDecDate,
    applyHolidayGiftCategorization,
    buildSubCategoriesByHigh,
    applyAndSyncCategoryCache,
    extractDeliveryDateInfo,
    findPageTimeFilterSelect,
    extractTimeFilters,
    extractAccountName,
    extractTotalOrdersCount,
    findOrderCardElements,
    parseOrdersPage,
    parseOrderCard,
    extractPaginationInfo,
    generateSingleHtmlReport
  };
})(typeof window !== 'undefined' ? window : globalThis);
